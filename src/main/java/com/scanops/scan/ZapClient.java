package com.scanops.scan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OWASP ZAP REST API 클라이언트
 *
 * ─ 설계 원칙 ────────────────────────────────────────────
 * - 모든 ZAP 호출은 공통 get() 헬퍼를 통해 수행
 * - 응답 null 및 HTTP 에러를 명시적으로 처리하여 NPE 방지
 * - api.disablekey=true 로 ZAP을 기동한 경우 ZAP_API_KEY 는 빈값으로 설정
 * ────────────────────────────────────────────────────────
 */
@Component
@Slf4j
public class ZapClient {

    private final WebClient    client;
    private final String       zapApiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ZapClient(
            WebClient.Builder webClientBuilder,
            @Value("${zap.host}") String zapHost,
            @Value("${zap.api-key}") String zapApiKey) {
        this.client = webClientBuilder
                .baseUrl(zapHost)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build())
                .build();
        this.zapApiKey = zapApiKey;
    }

    // ── Public API ───────────────────────────────────────

    /** ZAP 스캔 트리에 대상 URL 등록 */
    public void accessUrl(String targetUrl) {
        log.info("[ZAP] accessUrl: {}", targetUrl);
        Map<?, ?> response = get("/JSON/core/action/accessUrl/",
                Map.of("url", targetUrl, "followRedirects", "true"));
        log.info("[ZAP] accessUrl 응답: {}", response);
    }

    /**
     * 스파이더 스캔 시작
     * @return ZAP 스파이더 scan ID
     */
    public String startSpider(String targetUrl) {
        log.info("[ZAP] 스파이더 시작: {}", targetUrl);
        Map<?, ?> response = get("/JSON/spider/action/scan/",
                Map.of("url", targetUrl, "recurse", "true"));
        Object scanId = response.get("scan");
        if (scanId == null) {
            throw new RuntimeException("ZAP 스파이더 시작 실패 — 응답: " + response);
        }
        return String.valueOf(scanId);
    }

    /**
     * 스파이더 진행률 조회 (0~100)
     * @param scanId startSpider() 가 반환한 ID
     */
    public int getSpiderProgress(String scanId) {
        Map<?, ?> response = get("/JSON/spider/view/status/", Map.of("scanId", scanId));
        return parseIntSafe(response.get("status"));
    }

    /**
     * 액티브 스캔 시작
     * @return ZAP 액티브 스캔 scan ID
     */
    public String startActiveScan(String targetUrl) {
        log.info("[ZAP] 액티브 스캔 시작: {}", targetUrl);
        Map<?, ?> response = get("/JSON/ascan/action/scan/",
                Map.of("url", targetUrl, "recurse", "true"));
        Object scanId = response.get("scan");
        if (scanId == null) {
            throw new RuntimeException("ZAP 액티브 스캔 시작 실패 — 응답: " + response);
        }
        return String.valueOf(scanId);
    }

    /**
     * 액티브 스캔 진행률 조회 (0~100)
     * @param scanId startActiveScan() 이 반환한 ID
     */
    public int getActiveScanProgress(String scanId) {
        Map<?, ?> response = get("/JSON/ascan/view/status/", Map.of("scanId", scanId));
        return parseIntSafe(response.get("status"));
    }

    /**
     * 스캔 결과 알림 목록 조회
     * @param targetUrl 스캔한 대상 URL (baseurl 필터)
     */
    @SuppressWarnings("unchecked")
    public List<ZapAlert> getAlerts(String targetUrl) {
        log.info("[ZAP] 알림 조회: {}", targetUrl);
        Map<String, Object> response = get("/JSON/core/view/alerts/", Map.of("baseurl", targetUrl));

        List<?> rawAlerts = (List<?>) response.get("alerts");
        if (rawAlerts == null) {
            log.warn("[ZAP] alerts 필드가 null — 빈 목록 반환");
            return List.of();
        }

        return rawAlerts.stream()
                .map(a -> {
                    Map<?, ?> m = (Map<?, ?>) a;
                    return new ZapAlert(
                            str(m, "alert"),
                            str(m, "risk"),
                            str(m, "url"),
                            str(m, "param"),
                            str(m, "description"),
                            str(m, "solution"));
                })
                .collect(Collectors.toList());
    }

    // ── 내부 공통 HTTP 헬퍼 ─────────────────────────────

    /**
     * ZAP REST API GET 공통 호출.
     * - HTTP 에러(4xx/5xx) 를 RuntimeException 으로 변환
     * - 빈 응답 body 를 RuntimeException 으로 변환
     * - 로그에 path와 응답 내용을 기록하여 디버깅 용이
     */
    /**
     * ZAP은 JSON 응답에도 Content-Type: text/html 을 반환하는 경우가 있음.
     * WebClient의 bodyToMono(Map.class)는 Content-Type 기반으로 디코더를 선택하므로
     * text/html 응답은 null을 반환함.
     * → bodyToMono(String.class)로 raw 문자열을 받은 뒤 ObjectMapper로 직접 파싱.
     */
    private Map<String, Object> get(String path, Map<String, String> params) {
        try {
            String raw = client.get()
                    .uri(uri -> {
                        var builder = uri.path(path).queryParam("apikey", zapApiKey);
                        params.forEach(builder::queryParam);
                        return builder.build();
                    })
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            res -> res.bodyToMono(String.class).map(body -> {
                                log.error("[ZAP] HTTP 에러 {} — path={}, body={}", res.statusCode(), path, body);
                                return new RuntimeException(
                                        "ZAP HTTP 에러 " + res.statusCode() + " (" + path + "): " + body);
                            }))
                    .bodyToMono(String.class)
                    .block();

            if (raw == null || raw.isBlank()) {
                throw new RuntimeException("ZAP 빈 응답 — path=" + path);
            }

            log.debug("[ZAP] {} 응답: {}", path, raw);
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});

        } catch (WebClientResponseException e) {
            log.error("[ZAP] 요청 실패 — path={}, status={}, body={}",
                    path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("ZAP 요청 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("ZAP 응답 파싱 실패 — path=" + path + ": " + e.getMessage(), e);
        }
    }

    // ── 내부 유틸 ────────────────────────────────────────

    private String str(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private int parseIntSafe(Object val) {
        if (val == null) {
            throw new RuntimeException("ZAP 진행률 응답에 status 필드가 없음");
        }
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            throw new RuntimeException("ZAP 진행률 파싱 실패: " + val, e);
        }
    }
}

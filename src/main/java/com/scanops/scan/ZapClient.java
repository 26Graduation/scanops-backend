package com.scanops.scan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ZapClient {

    private final WebClient client;
    private final String zapApiKey;

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

    public void accessUrl(String targetUrl) {
        log.info("Seeding ZAP scan tree with {}", targetUrl);
        Map<?, ?> response = get("/JSON/core/action/accessUrl/",
                Map.of("url", targetUrl, "followRedirects", "true"));
        log.info("ZAP accessUrl response: {}", response);
    }

    public String startSpider(String targetUrl) {
        log.info("Starting ZAP spider for {}", targetUrl);
        Map<?, ?> response = get("/JSON/spider/action/scan/",
                Map.of("url", targetUrl, "recurse", "true"));
        Object scanId = response.get("scan");
        if (scanId == null) {
            throw new RuntimeException("ZAP spider start failed, response: " + response);
        }
        return String.valueOf(scanId);
    }

    public int getSpiderProgress(String scanId) {
        Map<?, ?> response = get("/JSON/spider/view/status/", Map.of("scanId", scanId));
        return Integer.parseInt(String.valueOf(response.get("status")));
    }

    public String startActiveScan(String targetUrl) {
        log.info("Starting ZAP active scan for {}", targetUrl);
        Map<?, ?> response = get("/JSON/ascan/action/scan/",
                Map.of("url", targetUrl, "recurse", "true"));
        Object scanId = response.get("scan");
        if (scanId == null) {
            throw new RuntimeException("ZAP active scan start failed, response: " + response);
        }
        return String.valueOf(scanId);
    }

    public int getActiveScanProgress(String scanId) {
        Map<?, ?> response = get("/JSON/ascan/view/status/", Map.of("scanId", scanId));
        return Integer.parseInt(String.valueOf(response.get("status")));
    }

    @SuppressWarnings("unchecked")
    public List<ZapAlert> getAlerts(String targetUrl) {
        log.info("Fetching ZAP alerts for {}", targetUrl);
        Map<?, ?> response = get("/JSON/core/view/alerts/", Map.of("baseurl", targetUrl));

        List<?> rawAlerts = (List<?>) response.get("alerts");
        if (rawAlerts == null) return List.of();

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

    private Map<?, ?> get(String path, Map<String, String> params) {
        try {
            Map<?, ?> response = client.get()
                    .uri(uri -> {
                        var builder = uri.path(path).queryParam("apikey", zapApiKey);
                        params.forEach(builder::queryParam);
                        return builder.build();
                    })
                    .retrieve()
                    .onStatus(status -> status.isError(), res ->
                            res.bodyToMono(String.class).map(body -> {
                                log.error("ZAP error {} for {}: {}", res.statusCode(), path, body);
                                return new RuntimeException("ZAP HTTP error " + res.statusCode() + ": " + body);
                            }))
                    .bodyToMono(Map.class)
                    .block();
            if (response == null) {
                throw new RuntimeException("ZAP returned empty response for " + path);
            }
            log.debug("ZAP {} response: {}", path, response);
            return response;
        } catch (WebClientResponseException e) {
            log.error("ZAP request failed for {}: {} {}", path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("ZAP request failed: " + e.getMessage(), e);
        }
    }

    private String str(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}

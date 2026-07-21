package com.scanops.scan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ScanOps Model API (FastAPI) 클라이언트
 * scripts/api_rebuild.py 서버와 통신 (2026-07 재구축 Qwen3.5-9B 단일 모델)
 *
 * 타임아웃: 모델이 RunPod Serverless로 라우팅되면 cold start(워커 기동+모델 로드)가
 * 첫 요청에 수 분 얹힐 수 있어 명시적으로 넉넉히 잡는다. 실패 시엔 기존대로
 * null/빈 결과로 graceful 처리되어 스캔 자체는 완료된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScanopsModelClient {

    @Value("${scanops.model.url:http://localhost:8100}")
    private String modelUrl;

    private final WebClient.Builder webClientBuilder;

    public record AnalyzeRequest(String language, String code, String file_path, boolean use_rag) {}

    public record CveReference(String cve_id, String severity, double base_score,
                                String cwe_id, String description) {}

    public record AnalyzeResult(
            String language, String file_path,
            boolean detected, int stage,
            String vulnerability, String severity,
            Double cvss_score,
            String reason,                       // rebuild: 모델 탐지 근거 한 줄 (영어)
            String attack, String fix,
            String summary,                      // 한줄 요약 (한국어, 메타 생성)
            List<CveReference> cve_references,
            double elapsed
    ) {}

    public record BatchRequest(List<AnalyzeRequest> files, boolean stop_on_first) {}

    public record BatchResult(int total, int detected_count,
                               List<AnalyzeResult> results, double elapsed) {}

    /** 코드 단건 분석 — 모델 서버 미연결 시 null 반환 */
    public AnalyzeResult analyze(String language, String code, String filePath) {
        try {
            return webClientBuilder.build()
                    .post()
                    .uri(modelUrl + "/analyze")
                    .bodyValue(new AnalyzeRequest(language, code, filePath, true))
                    .retrieve()
                    .bodyToMono(AnalyzeResult.class)
                    .timeout(Duration.ofSeconds(480))   // cold start + 판정 + 메타 생성
                    .block();
        } catch (Exception e) {
            log.warn("ScanOps model 서버 미연결 ({}): 단건 분석 생략", e.getMessage());
            return null;
        }
    }

    /** 파일 목록 일괄 분석 (GitHub 레포용) — 모델 서버 미연결 시 빈 결과 반환 */
    public BatchResult analyzeBatch(List<AnalyzeRequest> files) {
        try {
            BatchResult result = webClientBuilder.build()
                    .post()
                    .uri(modelUrl + "/analyze/batch")
                    .bodyValue(new BatchRequest(files, false))
                    .retrieve()
                    .bodyToMono(BatchResult.class)
                    .timeout(Duration.ofMinutes(30))    // 레포 전체 파일 순차 분석
                    .block();
            return result != null ? result : new BatchResult(0, 0, List.of(), 0);
        } catch (Exception e) {
            log.warn("ScanOps model 서버 미연결 ({}): 분석 없이 완료 처리", e.getMessage());
            return new BatchResult(0, 0, List.of(), 0);
        }
    }

    /** 서버 헬스 체크 */
    public boolean isHealthy() {
        try {
            Map<?, ?> result = webClientBuilder.build()
                    .get()
                    .uri(modelUrl + "/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return result != null && "ok".equals(result.get("status"));
        } catch (Exception e) {
            return false;
        }
    }
}

package com.scanops.scan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * ScanOps Model API (FastAPI) 클라이언트
 * scripts/api_rebuild.py 서버와 통신 (2026-07 재구축 Qwen3.5-9B 단일 모델)
 *
 * 타임아웃: 모델이 RunPod Serverless로 라우팅되면 cold start(워커 기동+모델 로드)가
 * 첫 요청에 수 분 얹힐 수 있어 명시적으로 넉넉히 잡는다. 실패 시엔 기존대로
 * null/빈 결과를 반환하며 호출자는 실패로 처리해야 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScanopsModelClient {

    @Value("${scanops.model.url:http://localhost:8100}")
    private String modelUrl;

    @Value("${scanops.api-key:}")
    private String apiKey;

    @Value("${scanops.java-model.url:}")
    private String javaModelUrl;

    @Value("${scanops.java-model.api-key:}")
    private String javaApiKey;

    @Value("${scanops.java-model.timeout-seconds:900}")
    private long javaTimeoutSeconds = 900;

    private final WebClient.Builder webClientBuilder;

    boolean routesToJava(String language) {
        return ("java".equalsIgnoreCase(language)
                || "java spring boot".equalsIgnoreCase(language))
                && javaModelUrl != null && !javaModelUrl.isBlank();
    }

    private String endpoint(boolean javaRoute) { return javaRoute ? javaModelUrl : modelUrl; }
    private String key(boolean javaRoute) {
        String key = javaRoute ? javaApiKey : apiKey;
        if (javaRoute && (key == null || key.isBlank()))
            throw new IllegalStateException("Java model API key is required");
        return key == null ? "" : key;
    }

    private void validate(AnalyzeResult r, AnalyzeRequest request, boolean javaRoute) {
        if (r == null || !Objects.equals(r.file_path(), request.file_path())
                || (javaRoute && !"DONE".equals(r.status()))
                || (r.status() != null && !"DONE".equals(r.status())))
            throw new IllegalStateException("Analysis incomplete or mismatched");
    }

    public record AnalyzeRequest(String language, String code, String file_path, boolean use_rag) {}

    public record CveReference(String cve_id, String severity, double base_score,
                                String cwe_id, String description) {}

    public record EngineFinding(String cwe, Integer line, String source,
                                String evidence_level, String reason,
                                List<Map<String, Object>> path,
                                List<String> contributors) {}

    public record AnalyzeResult(
            String language, String file_path,
            boolean detected, int stage,
            String vulnerability, String severity,
            Double cvss_score,
            String reason,                       // rebuild: 모델 탐지 근거 한 줄 (영어)
            String attack, String fix,
            String summary,                      // 한줄 요약 (한국어, 메타 생성)
            List<CveReference> cve_references,
            double elapsed,
            String status, String source, Integer line,
            List<Map<String, Object>> evidence, String ai_prompt,
            List<EngineFinding> findings, Map<String, Object> analysis_details
    ) {
        /** Legacy responses remain one row; ensemble findings are never collapsed by file. */
        public List<AnalyzeResult> individualResults() {
            if (!detected || findings == null || findings.isEmpty()) return List.of(this);
            return findings.stream().map(f -> {
                boolean representative = java.util.Objects.equals(vulnerability, f.cwe())
                        && java.util.Objects.equals(line, f.line());
                String label = "semantic-review".equals(f.evidence_level())
                        ? "LLM 의미 검토 후보 (CPG 경로 증명 아님)" : "CPG 정적 규칙 탐지 후보";
                String detail = f.reason() == null ? "" : f.reason();
                String advice = "해당 위치의 " + f.cwe() + " 후보를 검토하고 안전한 수정과 회귀 테스트를 추가하세요.";
                return new AnalyzeResult(language, file_path, true, stage, f.cwe(),
                        representative ? severity : "UNKNOWN", representative ? cvss_score : null,
                        label + (detail.isBlank() ? "" : ": " + detail),
                        representative ? attack : detail,
                        representative ? fix : advice,
                        representative ? summary : label,
                        representative && cve_references != null ? cve_references : List.of(),
                        elapsed, status, f.source(), f.line(), f.path(),
                        representative ? ai_prompt : "파일 " + file_path + ", 줄 " + f.line()
                                + ": " + advice + " 기존 동작을 보존하고 실제 악용 조건을 확인하세요.",
                        List.of(), analysis_details);
            }).toList();
        }
    }

    public record BatchRequest(List<AnalyzeRequest> files, boolean stop_on_first) {}

    public record BatchResult(int total, int detected_count,
                               List<AnalyzeResult> results, double elapsed) {}

    /** Transport/incomplete failures return null; callers must treat this as failure. */
    public AnalyzeResult analyze(String language, String code, String filePath) {
        boolean javaRoute = routesToJava(language);
        var request = new AnalyzeRequest(language, code, filePath, true);
        try {
            AnalyzeResult result = webClientBuilder.build().post()
                    .uri(endpoint(javaRoute) + "/analyze").header("X-API-Key", key(javaRoute))
                    .bodyValue(request).retrieve().bodyToMono(AnalyzeResult.class)
                    .timeout(Duration.ofSeconds(javaRoute ? javaTimeoutSeconds : 480)).block();
            validate(result, request, javaRoute);
            return result;
        } catch (Exception e) {
            log.warn("Model analysis failed ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Route independently, then restore input order. An incomplete partition fails the entire scan. */
    public BatchResult analyzeBatch(List<AnalyzeRequest> files) {
        try {
            List<AnalyzeResult> ordered = new ArrayList<>(java.util.Collections.nCopies(files.size(), null));
            double elapsed = 0;
            for (boolean javaRoute : new boolean[]{false, true}) {
                List<AnalyzeRequest> partition = files.stream()
                        .filter(f -> routesToJava(f.language()) == javaRoute).toList();
                if (partition.isEmpty()) continue;
                BatchResult result = webClientBuilder.build().post()
                        .uri(endpoint(javaRoute) + "/analyze/batch").header("X-API-Key", key(javaRoute))
                        .bodyValue(new BatchRequest(partition, false)).retrieve().bodyToMono(BatchResult.class)
                        .timeout(Duration.ofMinutes(30)).block();
                if (result == null || result.results() == null || result.total() != partition.size()
                        || result.results().size() != partition.size())
                    throw new IllegalStateException("Analysis files missing");
                int partitionIndex = 0;
                for (int i = 0; i < files.size(); i++) {
                    if (routesToJava(files.get(i).language()) != javaRoute) continue;
                    AnalyzeResult row = result.results().get(partitionIndex++);
                    validate(row, files.get(i), javaRoute);
                    ordered.set(i, row);
                }
                elapsed += result.elapsed();
            }
            return new BatchResult(files.size(), (int) ordered.stream().filter(AnalyzeResult::detected).count(), ordered, elapsed);
        } catch (Exception e) {
            log.warn("Model batch failed; caller must mark scan failed ({}: {})",
                    e.getClass().getSimpleName(), e.getMessage());
            return new BatchResult(0, 0, List.of(), 0);
        }
    }

    /** Preserve legacy PR semantics; route Java through the complete-file batch contract. */
    public JsonNode analyzePr(String repo, int prNumber, List<Map<String, Object>> files) {
        ObjectMapper mapper = new ObjectMapper();
        var findings = mapper.createArrayNode();
        var legacy = files.stream().filter(f -> !routesToJava(
                String.valueOf(f.get("filename")).toLowerCase().endsWith(".java") ? "Java" : "Other")).toList();
        double elapsed = 0;
        if (!legacy.isEmpty()) {
            JsonNode response = webClientBuilder.build().post().uri(modelUrl + "/analyze/pr")
                    .header("X-API-Key", key(false))
                    .bodyValue(Map.of("repo", repo, "pr_number", prNumber, "files", legacy))
                    .retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofMinutes(30)).block();
            if (response == null || !response.path("findings").isArray()
                    || response.path("total_files").asInt(-1) != legacy.size())
                throw new IllegalStateException("Legacy PR response incomplete");
            for (JsonNode finding : response.path("findings")) {
                if (finding.hasNonNull("status") && !"DONE".equals(finding.path("status").asText()))
                    throw new IllegalStateException("Legacy PR analysis incomplete");
                if (legacy.stream().noneMatch(f -> Objects.equals(f.get("filename"), finding.path("filename").asText())))
                    throw new IllegalStateException("Unexpected PR file");
                findings.add(finding);
            }
            elapsed += response.path("elapsed").asDouble();
        }
        var javaFiles = files.stream().filter(f -> !legacy.contains(f))
                .map(f -> new AnalyzeRequest("Java", (String) f.get("content"), (String) f.get("filename"), true)).toList();
        if (!javaFiles.isEmpty()) {
            BatchResult result = analyzeBatch(javaFiles);
            if (result.total() != javaFiles.size()) throw new IllegalStateException("Java PR analysis incomplete");
            result.results().stream().flatMap(r -> r.individualResults().stream()).filter(AnalyzeResult::detected)
                    .forEach(r -> {
                        ObjectNode row = mapper.valueToTree(r);
                        row.put("filename", r.file_path());
                        if (r.line() != null) row.put("diff_line", r.line());
                        findings.add(row);
                    });
            elapsed += result.elapsed();
        }
        // Keep file order even when the request mixes languages.
        var ordered = mapper.createArrayNode();
        for (var file : files) for (var finding : findings)
            if (Objects.equals(file.get("filename"), finding.path("filename").asText())) ordered.add(finding);
        ObjectNode response = mapper.createObjectNode();
        response.put("total_files", files.size());
        response.put("vulnerable_count", (int) java.util.stream.StreamSupport.stream(ordered.spliterator(), false)
                .filter(f -> f.path("detected").asBoolean()).count());
        response.put("elapsed", elapsed);
        response.set("findings", ordered);
        return response;
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

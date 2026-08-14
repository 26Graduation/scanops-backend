package com.scanops.scan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * GitHub Action에서 호출하는 PR diff 보안 스캔 엔드포인트
 * POST /api/github/pr-scan
 */
@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
@Slf4j
public class PrScanController {

    @Value("${scanops.api-key:}")
    private String apiKey;

    private final ScanopsModelClient modelClient;
    private final GithubScanService githubScanService;
    private final PrScanBilling prScanBilling;

    @PostMapping("/pr-scan")
    public ResponseEntity<?> scanPr(
            @RequestHeader(value = "X-Scanops-Key", required = false) String key,
            @RequestBody PrScanRequest req) {

        if (apiKey != null && !apiKey.isBlank() && !apiKey.equals(key)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid API key"));
        }

        log.info("[PR 스캔] repo={} pr={} files={}",
                req.repo(), req.prNumber(), req.files().size());

        List<ScanopsModelClient.AnalyzeRequest> requests = req.files().stream()
                .filter(f -> f.content() != null && !f.content().isBlank())
                .map(f -> {
                    String lang = githubScanService.detectLanguage(f.filename());
                    return lang == null ? null
                            : new ScanopsModelClient.AnalyzeRequest(lang, f.content(), f.filename(), true);
                })
                .filter(r -> r != null)
                .toList();

        if (requests.isEmpty()) {
            return ResponseEntity.ok(new PrScanResponse(
                    req.repo(), req.prNumber(), 0, 0, List.of(), 0));
        }

        // 변경 코드 줄 수로 예약 → 분석 결과가 비면(모델 서버 미연결) 전액 반환.
        long changedLines = requests.stream()
                .mapToLong(r -> GithubScanService.countLines(r.code()))
                .sum();
        var billingKey = prScanBilling.hold(
                req.ownerGithubId(), req.repo(), req.prNumber(), contentDigest(requests), changedLines);

        ScanopsModelClient.BatchResult batch = modelClient.analyzeBatch(requests);

        if (batch.total() == 0) {
            prScanBilling.release(billingKey, "PR 분석 무응답 — 무과금");
            log.warn("[PR 스캔] 분석 엔진 미응답 — 무과금 처리: {}#{}", req.repo(), req.prNumber());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "분석 엔진에 연결하지 못했어요. 토큰은 차감되지 않았습니다."));
        }
        prScanBilling.commit(billingKey, changedLines);

        // AnalyzeResult → PrScanFinding 변환 (patch의 diff_line은 Action이 전달)
        List<PrScanFinding> findings = batch.results().stream()
                .map(r -> {
                    Integer diffLine = req.files().stream()
                            .filter(f -> f.filename().equals(r.file_path()))
                            .findFirst()
                            .map(f -> extractFirstAddedLine(f.patch()))
                            .orElse(null);
                    return new PrScanFinding(
                            r.file_path(),
                            r.detected(),
                            r.vulnerability(),
                            r.severity(),
                            r.cvss_score(),
                            r.attack(),
                            r.fix(),
                            r.cve_references(),
                            diffLine
                    );
                })
                .toList();

        PrScanResponse response = new PrScanResponse(
                req.repo(), req.prNumber(),
                batch.total(), batch.detected_count(),
                findings, batch.elapsed());

        log.info("[PR 스캔] 완료: 취약점 {}개 / {}개 파일", batch.detected_count(), batch.total());
        return ResponseEntity.ok(response);
    }

    /**
     * 멱등 키에 쓸 내용 지문. Action 경로에는 커밋 SHA가 없어 파일 경로+길이로 대신한다.
     * 같은 diff를 재시도하면 이중 과금되지 않고, 코드가 바뀌면 새로 과금된다.
     */
    private String contentDigest(List<ScanopsModelClient.AnalyzeRequest> requests) {
        int hash = requests.stream()
                .map(r -> r.file_path() + ":" + (r.code() == null ? 0 : r.code().length()))
                .reduce("", (a, b) -> a + "|" + b)
                .hashCode();
        return Integer.toHexString(hash);
    }

    /** patch 문자열에서 첫 번째 추가 라인 번호 추출 (@@ -n,m +start ... 형식) */
    private Integer extractFirstAddedLine(String patch) {
        if (patch == null || patch.isBlank()) return null;
        var matcher = java.util.regex.Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)").matcher(patch);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }
}

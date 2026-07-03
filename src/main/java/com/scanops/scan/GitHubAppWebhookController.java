package com.scanops.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanops.github.GithubAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * GitHub App Webhook 수신 → PR 자동 보안 스캔
 * POST /api/github/webhook
 */
@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
@Slf4j
public class GitHubAppWebhookController {

    @Value("${github.webhook.secret:}")
    private String webhookSecret;

    @Value("${scanops.model.url:http://localhost:8100}")
    private String modelUrl;

    @Value("${scanops.api-key:}")
    private String scanopsApiKey;

    private final GithubScanService githubScanService;
    private final GithubAppService githubAppService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> TARGET_EXTS = Set.of(
        ".java", ".kt", ".jsx", ".tsx", ".js", ".ts",
        ".py", ".go", ".rs", ".c", ".cpp", ".php", ".rb"
    );

    // ── Webhook 수신 ──────────────────────────────────────────────────────────

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "") String event,
            @RequestHeader(value = "X-Hub-Signature-256", defaultValue = "") String signature,
            @RequestBody String payload) {

        if (!webhookSecret.isBlank() && !verifySignature(payload, signature)) {
            log.warn("[Webhook] 서명 검증 실패");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        if (!"pull_request".equals(event)) {
            return ResponseEntity.ok("ignored");
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = root.path("action").asText();

            if (!Set.of("opened", "synchronize", "reopened").contains(action)) {
                return ResponseEntity.ok("ignored action: " + action);
            }

            long   installationId = root.path("installation").path("id").asLong();
            int    prNumber       = root.path("number").asInt();
            String repoFullName   = root.path("repository").path("full_name").asText();
            String headSha        = root.path("pull_request").path("head").path("sha").asText();
            String headRepoFullName = root.path("pull_request").path("head").path("repo").path("full_name").asText();
            if (headRepoFullName.isBlank()) headRepoFullName = repoFullName;

            log.info("[Webhook] PR #{} repo={} action={}", prNumber, repoFullName, action);

            final String finalHeadRepo = headRepoFullName;
            new Thread(() -> processPr(installationId, repoFullName, finalHeadRepo, prNumber, headSha)).start();

        } catch (Exception e) {
            log.error("[Webhook] 파싱 오류: {}", e.getMessage());
        }

        return ResponseEntity.ok("ok");
    }

    // ── PR 처리 ───────────────────────────────────────────────────────────────

    private void processPr(long installationId, String repo, String headRepo, int prNumber, String headSha) {
        String[] parts    = repo.split("/", 2);
        String owner      = parts[0];
        String repoName   = parts[1];
        String[] headParts  = headRepo.split("/", 2);
        String headOwner    = headParts[0];
        String headRepoName = headParts[1];

        try {
            String token = githubAppService.getInstallationToken(installationId);
            if (token == null) { log.error("[Webhook] 토큰 발급 실패"); return; }

            WebClient gh = WebClient.builder()
                    .baseUrl("https://api.github.com")
                    .defaultHeader("Authorization", "Bearer " + token)
                    .defaultHeader("Accept", "application/vnd.github+json")
                    .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                    .build();

            // 1. 분석 시작 알림 (Commit Status: pending)
            postCommitStatus(gh, headOwner, headRepoName, headSha, "pending",
                    "ScanOps 보안 분석 중...", "scanops/security");

            // 2. PR 변경 파일 목록
            JsonNode filesNode = gh.get()
                    .uri("/repos/{owner}/{repo}/pulls/{pr}/files?per_page=50", owner, repoName, prNumber)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (filesNode == null || !filesNode.isArray()) {
                log.warn("[Webhook] PR 파일 목록 없음");
                postCommitStatus(gh, headOwner, headRepoName, headSha, "failure", "파일 목록 조회 실패", "scanops/security");
                return;
            }

            // 3. 파일 내용 + patch 수집
            List<Map<String, Object>> prFiles = new ArrayList<>();
            filesNode.forEach(f -> {
                String filename = f.path("filename").asText();
                String status   = f.path("status").asText();
                if ("removed".equals(status)) return;

                String ext = filename.contains(".")
                        ? filename.substring(filename.lastIndexOf('.')).toLowerCase() : "";
                if (!TARGET_EXTS.contains(ext)) return;

                try {
                    String contentB64 = gh.get()
                            .uri("/repos/{owner}/{repo}/contents/{path}?ref={sha}", owner, repoName, filename, headSha)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block()
                            .path("content").asText()
                            .replaceAll("\\s", "");

                    String content = new String(Base64.getDecoder().decode(contentB64), StandardCharsets.UTF_8);
                    if (content.length() > 10000) content = content.substring(0, 10000);

                    Map<String, Object> file = new HashMap<>();
                    file.put("filename", filename);
                    file.put("content", content);
                    file.put("patch", f.path("patch").asText(""));
                    prFiles.add(file);

                } catch (Exception e) {
                    log.warn("[Webhook] 파일 읽기 실패: {}", filename);
                }
            });

            if (prFiles.isEmpty()) {
                postCommitStatus(gh, headOwner, headRepoName, headSha, "success", "분석 대상 파일 없음", "scanops/security");
                return;
            }

            // 4. scanops-model /analyze/pr 호출 (파일당 여러 취약점 타입 반환)
            WebClient model = WebClient.builder()
                    .baseUrl(modelUrl)
                    .defaultHeader("Content-Type", "application/json")
                    .defaultHeader("X-Scanops-Key", scanopsApiKey)
                    .build();

            Map<String, Object> prScanReq = Map.of(
                    "repo", repo,
                    "pr_number", prNumber,
                    "files", prFiles
            );

            JsonNode prScanResp;
            try {
                prScanResp = model.post()
                        .uri("/analyze/pr")
                        .bodyValue(prScanReq)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();
            } catch (Exception e) {
                log.error("[Webhook] 모델 API 호출 실패: {}", e.getMessage());
                postCommitStatus(gh, headOwner, headRepoName, headSha, "failure", "모델 분석 실패", "scanops/security");
                return;
            }

            if (prScanResp == null) {
                postCommitStatus(gh, headOwner, headRepoName, headSha, "failure", "모델 응답 없음", "scanops/security");
                return;
            }

            // 5. 결과 처리
            JsonNode findings = prScanResp.path("findings");
            int vulnCount = prScanResp.path("vulnerable_count").asInt();

            log.info("[Webhook] PR #{} 분석 완료: 취약점 {}개", prNumber, vulnCount);

            if (vulnCount == 0) {
                postComment(gh, owner, repoName, prNumber,
                        "## 🔒 ScanOps 보안 스캔 결과\n\n✅ **취약점이 발견되지 않았습니다.**\n\n" +
                        "분석 파일: **" + prFiles.size() + "개**\n\n> Powered by [ScanOps](https://github.com/26Graduation)");
                postCommitStatus(gh, headOwner, headRepoName, headSha, "success", "취약점 없음", "scanops/security");
                return;
            }

            // 6. 요약 테이블 + 인라인 댓글
            StringBuilder summary = new StringBuilder();
            summary.append("## 🔍 ScanOps 보안 스캔 결과\n\n")
                   .append("> **").append(vulnCount).append("개 취약점 발견** | 분석 파일: ")
                   .append(prFiles.size()).append("개\n\n")
                   .append("| 심각도 | 파일 | 취약점 유형 | 위치 |\n|--------|------|------------|------|\n");

            List<Map<String, Object>> reviewComments = new ArrayList<>();

            findings.forEach(finding -> {
                if (!finding.path("detected").asBoolean()) return;

                String vuln     = finding.path("vulnerability").asText();
                String sev      = finding.path("severity").asText("—");
                String filename = finding.path("filename").asText();
                String attack   = finding.path("attack").asText("—");
                String fix      = finding.path("fix").asText("—");
                double cvss     = finding.path("cvss_score").asDouble(0);
                int diffLine    = finding.path("diff_line").asInt(0);

                String emoji    = severityEmoji(sev);
                String cvssStr  = cvss > 0 ? " (CVSS " + cvss + ")" : "";
                String loc      = diffLine > 0 ? diffLine + "번째 줄" : "줄 특정 불가";

                summary.append("| ").append(emoji).append(" **").append(sev).append(cvssStr)
                       .append("** | `").append(filename).append("` | ")
                       .append(vuln).append(" | ").append(loc).append(" |\n");

                // CVE 목록
                StringBuilder cveText = new StringBuilder();
                finding.path("cve_references").forEach(c -> {
                    String cveId = c.path("cve_id").asText("");
                    if (!cveId.isBlank() && !"N/A".equals(cveId)) {
                        cveText.append("- `").append(cveId).append("` (")
                               .append(c.path("severity").asText()).append(", ")
                               .append(c.path("cwe_id").asText()).append(")\n");
                    }
                });

                String cvssLine = cvss > 0 ? "\n**CVSS Score:** " + cvss : "";
                String body = "### " + emoji + " [ScanOps] " + vuln + "\n" +
                              "**파일:** `" + filename + "` | **심각도:** " + sev + cvssLine + "\n" +
                              "**위치:** " + loc + "\n\n" +
                              "**공격 시나리오:**\n" + attack + "\n\n" +
                              "**수정 방법:**\n" + fix +
                              (cveText.length() > 0 ? "\n\n**관련 CVE:**\n" + cveText : "");

                if (diffLine > 0) {
                    Map<String, Object> comment = new HashMap<>();
                    comment.put("path", filename);
                    comment.put("line", diffLine);
                    comment.put("body", body);
                    comment.put("side", "RIGHT");
                    reviewComments.add(comment);
                } else {
                    // 줄 특정 불가 → 일반 댓글
                    postComment(gh, owner, repoName, prNumber, body);
                }
            });

            summary.append("\n> Powered by [ScanOps](https://github.com/26Graduation)");

            // 7. PR Review 제출 (인라인 댓글 + 요약)
            try {
                Map<String, Object> review = new HashMap<>();
                review.put("commit_id", headSha);
                review.put("body", summary.toString());
                review.put("event", "COMMENT");
                review.put("comments", reviewComments);

                gh.post()
                  .uri("/repos/{owner}/{repo}/pulls/{pr}/reviews", owner, repoName, prNumber)
                  .bodyValue(review)
                  .retrieve()
                  .bodyToMono(String.class)
                  .block();
            } catch (Exception e) {
                log.warn("[Webhook] 인라인 댓글 실패, 요약만 일반 댓글로: {}", e.getMessage());
                postComment(gh, owner, repoName, prNumber, summary.toString());
            }

            // 8. 완료 상태 업데이트
            // 주의: GitHub statuses API description은 non-BMP 이모지(🔴 등) 거부 → 텍스트만 사용
            postCommitStatus(gh, headOwner, headRepoName, headSha, "failure",
                    "취약점 " + vulnCount + "개 발견", "scanops/security");

        } catch (Exception e) {
            log.error("[Webhook] processPr 오류: {}", e.getMessage(), e);
            try {
                String token = githubAppService.getInstallationToken(installationId);
                if (token != null) {
                    WebClient gh = WebClient.builder().baseUrl("https://api.github.com")
                            .defaultHeader("Authorization", "Bearer " + token)
                            .defaultHeader("Accept", "application/vnd.github+json").build();
                    postCommitStatus(gh, headOwner, headRepoName, headSha, "failure", "스캔 오류 발생", "scanops/security");
                }
            } catch (Exception ignored) {}
        }
    }

    // ── Commit Status ─────────────────────────────────────────────────────────

    private void postCommitStatus(WebClient gh, String owner, String repoName,
                                   String sha, String state, String description, String context) {
        try {
            gh.post()
              .uri("/repos/{owner}/{repo}/statuses/{sha}", owner, repoName, sha)
              .bodyValue(Map.of(
                  "state", state,
                  "description", description,
                  "context", context
              ))
              .retrieve()
              .bodyToMono(String.class)
              .block();
            log.info("[Webhook] Commit Status → {} : {}", state, description);
        } catch (Exception e) {
            log.warn("[Webhook] Commit Status 실패: {}", e.getMessage());
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private void postComment(WebClient gh, String owner, String repoName, int pr, String body) {
        try {
            gh.post()
              .uri("/repos/{owner}/{repo}/issues/{pr}/comments", owner, repoName, pr)
              .bodyValue(Map.of("body", body))
              .retrieve()
              .bodyToMono(String.class)
              .block();
        } catch (Exception e) {
            log.error("[Webhook] 댓글 작성 실패: {}", e.getMessage());
        }
    }

    private String severityEmoji(String sev) {
        if (sev == null) return "⚠️";
        return switch (sev.toUpperCase()) {
            case "CRITICAL", "HIGH" -> "🔴";
            case "MEDIUM" -> "🟡";
            case "LOW"    -> "🟢";
            default       -> "⚠️";
        };
    }

    private boolean verifySignature(String payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            return expected.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }
}

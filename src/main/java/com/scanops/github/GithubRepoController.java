package com.scanops.github;

import com.scanops.auth.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * 로그인한 사용자가 소유한 GitHub 레포 목록을 조회한다.
 * 사용자의 OAuth 토큰은 저장하지 않으므로, JWT의 github login으로
 * 공개 엔드포인트 GET /users/{login}/repos 를 호출한다(소유한 공개 레포).
 * github.token(앱 토큰)이 설정돼 있으면 인증 호출로 레이트리밋을 높인다.
 */
@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
@Slf4j
public class GithubRepoController {

    private final JwtService jwtService;
    private final WebClient.Builder webClientBuilder;

    @Value("${github.token:}")
    private String githubToken;

    @GetMapping("/repos")
    public ResponseEntity<?> repos(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다"));
        }

        String login;
        try {
            Claims c = jwtService.parse(authorization.substring(7));
            login = c.get("login", String.class);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "유효하지 않은 토큰입니다"));
        }
        if (login == null || login.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "GitHub 계정 정보가 없습니다"));
        }

        try {
            WebClient client = webClientBuilder
                    .baseUrl("https://api.github.com")
                    .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                    .build();

            List<Map<String, Object>> raw = client.get()
                    .uri(uri -> uri.path("/users/{login}/repos")
                            .queryParam("type", "owner")
                            .queryParam("sort", "pushed")
                            .queryParam("per_page", 100)
                            .build(login))
                    .headers(h -> {
                        if (githubToken != null && !githubToken.isBlank()) {
                            h.setBearerAuth(githubToken);
                        }
                    })
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .block();

            if (raw == null) raw = List.of();

            List<Map<String, Object>> repos = raw.stream().map(r -> {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("id", r.get("id"));
                m.put("fullName", r.get("full_name"));
                m.put("private", Boolean.TRUE.equals(r.get("private")));
                m.put("defaultBranch", r.getOrDefault("default_branch", "main"));
                m.put("htmlUrl", r.get("html_url"));
                m.put("language", r.get("language"));
                m.put("pushedAt", r.get("pushed_at"));
                return m;
            }).toList();

            return ResponseEntity.ok(repos);
        } catch (Exception e) {
            log.warn("GitHub repo list failed for {}: {}", login, e.getMessage());
            return ResponseEntity.status(502)
                    .body(Map.of("error", "GitHub 레포 목록을 불러오지 못했습니다"));
        }
    }
}

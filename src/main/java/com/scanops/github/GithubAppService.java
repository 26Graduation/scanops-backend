package com.scanops.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.StringReader;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;

/**
 * GitHub App 인증 공통 로직.
 *
 * <p>사용자의 PAT(개인 토큰)를 받지 않는다. 레포 주인이 ScanOps GitHub App을 설치하면
 * (= 소유권 증명), 서버가 App 개인키(.pem)로 App JWT를 만들고 이를 <b>수명 짧은(약 1시간)
 * 설치 토큰</b>으로 교환해 그 레포에만 접근한다. 설치 토큰은 저장하지 않고 매번 즉석 발급 후 폐기.
 *
 * <p>웹훅(PR 자동 분석)과 레포 SAST가 이 서비스를 공유한다. 새 DB 테이블을 만들지 않는다
 * (설치 여부는 GitHub API로 실시간 조회).
 */
@Service
@Slf4j
public class GithubAppService {

    @Value("${github.app.id:}")
    private String appId;

    @Value("${github.app.private-key:}")
    private String privateKeyPem;

    /** github.com/apps/&lt;slug&gt; 의 slug. 설치 URL 생성에 사용. */
    @Value("${github.app.slug:}")
    private String appSlug;

    /** App ID·개인키가 설정돼 있어야 App 기능 사용 가능. */
    public boolean isConfigured() {
        return appId != null && !appId.isBlank()
                && privateKeyPem != null && !privateKeyPem.isBlank();
    }

    /** 사용자가 레포를 선택해 App을 설치하는 GitHub 화면 URL. */
    public String installUrl() {
        String slug = appSlug == null ? "" : appSlug.trim();
        return slug.isBlank()
                ? "https://github.com/settings/installations"
                : "https://github.com/apps/" + slug + "/installations/new";
    }

    // ── App JWT / 설치 토큰 ────────────────────────────────────────────────────

    /** App 인증용 JWT(9분 유효). */
    public String generateJwt() throws Exception {
        String pem = privateKeyPem.replace("\\n", "\n").replace("\\r", "").trim();
        if (!pem.contains("-----BEGIN")) {
            pem = "-----BEGIN RSA PRIVATE KEY-----\n" + pem + "\n-----END RSA PRIVATE KEY-----";
        }

        PrivateKey privateKey;
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj == null) {
                throw new IllegalArgumentException("PEM 파싱 실패 (github.app.private-key 확인)");
            }
            JcaPEMKeyConverter conv = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair keyPair) {           // PKCS#1 (BEGIN RSA PRIVATE KEY) — GitHub 기본
                privateKey = conv.getKeyPair(keyPair).getPrivate();
            } else if (obj instanceof PrivateKeyInfo pki) {    // PKCS#8 (BEGIN PRIVATE KEY)
                privateKey = conv.getPrivateKey(pki);
            } else {
                throw new IllegalArgumentException("지원하지 않는 PEM 형식: " + obj.getClass());
            }
        }

        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(appId)
                .issuedAt(Date.from(now.minusSeconds(60)))
                .expiration(Date.from(now.plusSeconds(540)))
                .signWith(privateKey)
                .compact();
    }

    /** App JWT로 인증한 api.github.com 클라이언트. */
    private WebClient appClient() throws Exception {
        return WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + generateJwt())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** 설치 ID → 수명 짧은 설치 토큰(약 1시간). 실패 시 null. */
    public String getInstallationToken(long installationId) {
        try {
            JsonNode resp = appClient().post()
                    .uri("/app/installations/{id}/access_tokens", installationId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            return resp != null ? resp.path("token").asText(null) : null;
        } catch (Exception e) {
            log.error("[GithubApp] 설치 토큰 발급 실패(id={}): {}", installationId, e.getMessage());
            return null;
        }
    }

    /** owner/repo에 App이 설치돼 있으면 설치 ID, 아니면 null. */
    public Long findInstallationId(String owner, String repo) {
        try {
            JsonNode resp = appClient().get()
                    .uri("/repos/{owner}/{repo}/installation", owner, repo)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return null;
            long id = resp.path("id").asLong(0);
            return id > 0 ? id : null;
        } catch (Exception e) {
            // 설치 안 됨(404) 등 — 프라이빗 접근 불가로 처리
            log.info("[GithubApp] 설치 조회 실패 {}/{}: {}", owner, repo, e.getMessage());
            return null;
        }
    }

    /**
     * owner/repo 접근용 설치 토큰. App이 그 레포에 설치돼 있지 않으면 null
     * (→ 프라이빗 레포는 접근 불가, 공개 레포는 토큰 없이도 조회 가능).
     */
    public String tokenForRepo(String owner, String repo) {
        if (!isConfigured()) return null;
        Long id = findInstallationId(owner, repo);
        return id == null ? null : getInstallationToken(id);
    }
}

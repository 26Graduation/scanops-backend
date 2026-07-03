package com.scanops.verify;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 웹 도메인 소유권 인증 (DAST 전제조건). <b>사용자별</b>로 분리한다.
 *
 * <p>같은 도메인이라도 사용자마다 (ownerId, domain) 레코드와 토큰이 따로다.
 * 다른 사람이 같은 URL을 넣어도 본인 명의의 인증 레코드가 없고 토큰도 달라서 통과하지 못한다.
 */
@Service
@RequiredArgsConstructor
public class DomainVerifyService {

    /** 사용자가 자기 도메인에 올려야 하는 인증 파일 경로. */
    public static final String VERIFY_PATH = "/.well-known/scanops-verify.txt";

    private final DomainVerificationRepository domainVerificationRepository;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER) // 리다이렉트로 우회 못 하게
            .build();

    /** DB 플래그 기준 인증 여부(빠른 조회, 상태 표시용). */
    public boolean isVerified(String ownerId, String targetUrl) {
        return domainVerificationRepository
                .findByOwnerIdAndDomain(ownerId, extractDomain(targetUrl))
                .map(DomainVerification::isVerified)
                .orElse(false);
    }

    /** 인증 시작: (사용자, 도메인)별 토큰 발급(이미 있으면 재사용). */
    public DomainVerification initFromUrl(String ownerId, String targetUrl) {
        String domain = extractDomain(targetUrl);
        return domainVerificationRepository.findByOwnerIdAndDomain(ownerId, domain)
                .orElseGet(() -> domainVerificationRepository.save(
                        DomainVerification.builder()
                                .ownerId(ownerId)
                                .domain(domain)
                                .verifyToken(newToken())
                                .verified(false)
                                .build()));
    }

    /**
     * 사용자가 배포한 .well-known 파일을 fetch해 토큰 대조 → 성공 시 verified=true.
     * 인증 화면의 "확인" 버튼용.
     */
    public boolean confirmByFetch(String ownerId, String targetUrl) {
        String domain = extractDomain(targetUrl);
        DomainVerification dv = domainVerificationRepository
                .findByOwnerIdAndDomain(ownerId, domain).orElse(null);
        if (dv == null) return false;

        boolean ok = fetchMatches(domain, dv.getVerifyToken());
        if (ok) {
            dv.setVerified(true);
            dv.setVerifiedAt(LocalDateTime.now());
            domainVerificationRepository.save(dv);
        }
        return ok;
    }

    /**
     * 스캔 직전 재검증(TOCTOU 방지). 저장된 플래그를 믿지 않고 파일을 다시 받아
     * <b>지금도 토큰이 존재하는지</b> 확인한다. 도메인이 매각/만료되거나 파일이 지워졌으면 false.
     */
    public boolean verifyForScan(String ownerId, String targetUrl) {
        String domain = extractDomain(targetUrl);
        DomainVerification dv = domainVerificationRepository
                .findByOwnerIdAndDomain(ownerId, domain).orElse(null);
        if (dv == null || dv.getVerifyToken() == null) return false;

        boolean ok = fetchMatches(domain, dv.getVerifyToken());
        // 현재 상태를 반영: 성공하면 verified 유지/갱신, 실패하면 내려서 재인증 유도
        dv.setVerified(ok);
        if (ok) dv.setVerifiedAt(LocalDateTime.now());
        domainVerificationRepository.save(dv);
        return ok;
    }

    private boolean fetchMatches(String domain, String expectedToken) {
        return fetchToken("https://" + domain + VERIFY_PATH, expectedToken)
                || fetchToken("http://" + domain + VERIFY_PATH, expectedToken);
    }

    private boolean fetchToken(String url, String expectedToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "ScanOps-DomainVerifier")
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 && res.body() != null
                    && res.body().trim().contains(expectedToken);
        } catch (Exception e) {
            return false;
        }
    }

    /** 사용자·도메인마다 서로 다른 예측 불가 토큰. */
    private String newToken() {
        return "scanops-verify-" + UUID.randomUUID();
    }

    public String extractDomain(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host != null ? host : url.trim();
        } catch (Exception e) {
            return url.trim();
        }
    }
}

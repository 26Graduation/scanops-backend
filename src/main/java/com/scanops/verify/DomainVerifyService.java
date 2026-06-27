package com.scanops.verify;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

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

    public boolean isVerified(String targetUrl) {
        return domainVerificationRepository.findByDomain(extractDomain(targetUrl))
                .map(DomainVerification::isVerified)
                .orElse(false);
    }

    /** 인증 시작: 도메인에 대한 토큰 발급(이미 있으면 재사용). */
    public DomainVerification initFromUrl(String targetUrl) {
        String domain = extractDomain(targetUrl);
        return domainVerificationRepository.findByDomain(domain)
                .orElseGet(() -> domainVerificationRepository.save(
                        DomainVerification.builder()
                                .domain(domain)
                                .verifyToken(UUID.randomUUID().toString())
                                .verified(false)
                                .build()));
    }

    /**
     * 실제 확인: https://<domain>/.well-known/scanops-verify.txt 를 직접 받아
     * 내용이 발급 토큰과 일치하면 verified=true. (사용자 설계의 매 스캔 직전 검증과 동일 원리)
     */
    public boolean confirmByFetch(String targetUrl) {
        String domain = extractDomain(targetUrl);
        DomainVerification dv = domainVerificationRepository.findByDomain(domain).orElse(null);
        if (dv == null) return false;

        boolean ok = fetchToken("https://" + domain + VERIFY_PATH, dv.getVerifyToken())
                || fetchToken("http://" + domain + VERIFY_PATH, dv.getVerifyToken());
        if (ok && !dv.isVerified()) {
            dv.setVerified(true);
            domainVerificationRepository.save(dv);
        }
        return ok;
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

    public String extractDomain(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host != null ? host : url.trim();
        } catch (Exception e) {
            return url.trim();
        }
    }
}

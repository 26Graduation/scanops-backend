package com.scanops.verify;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DomainVerifyService {

    private final DomainVerificationRepository domainVerificationRepository;

    public boolean isVerified(String targetUrl) {
        String domain = extractDomain(targetUrl);
        return domainVerificationRepository.findByDomain(domain)
                .map(DomainVerification::isVerified)
                .orElse(false);
    }

    public DomainVerification initVerification(String domain) {
        return domainVerificationRepository.findByDomain(domain)
                .orElseGet(() -> {
                    DomainVerification dv = DomainVerification.builder()
                            .domain(domain)
                            .verifyToken(UUID.randomUUID().toString())
                            .verified(false)
                            .build();
                    return domainVerificationRepository.save(dv);
                });
    }

    public boolean confirm(String domain, String token) {
        return domainVerificationRepository.findByDomain(domain)
                .filter(dv -> dv.getVerifyToken().equals(token))
                .map(dv -> {
                    dv.setVerified(true);
                    domainVerificationRepository.save(dv);
                    return true;
                })
                .orElse(false);
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }
}

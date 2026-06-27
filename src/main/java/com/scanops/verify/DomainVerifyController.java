package com.scanops.verify;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 웹 도메인 소유권 인증 (DAST 스캔 전제조건).
 * 방식: 토큰 발급 → 사용자가 /.well-known/scanops-verify.txt 에 배포 → 백엔드가 fetch 검증.
 */
@RestController
@RequestMapping("/api/verify")
@RequiredArgsConstructor
public class DomainVerifyController {

    private final DomainVerifyService service;

    /** 인증 시작 — 토큰과 업로드 경로 발급. */
    @PostMapping("/domain")
    public ResponseEntity<?> init(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("url", "").trim();
        if (url.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "url이 필요합니다"));
        DomainVerification dv = service.initFromUrl(url);
        return ResponseEntity.ok(Map.of(
                "domain", dv.getDomain(),
                "token", dv.getVerifyToken(),
                "path", DomainVerifyService.VERIFY_PATH,
                "verified", dv.isVerified()
        ));
    }

    /** 실제 확인 — .well-known 파일을 fetch해 토큰 대조. */
    @PostMapping("/domain/confirm")
    public ResponseEntity<?> confirm(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("url", "").trim();
        if (url.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "url이 필요합니다"));
        boolean verified = service.confirmByFetch(url);
        return ResponseEntity.ok(Map.of("verified", verified));
    }

    /** 현재 인증 상태. */
    @GetMapping("/domain")
    public ResponseEntity<?> status(@RequestParam String url) {
        return ResponseEntity.ok(Map.of("verified", service.isVerified(url)));
    }
}

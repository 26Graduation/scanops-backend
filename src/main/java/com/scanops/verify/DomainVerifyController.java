package com.scanops.verify;

import com.scanops.auth.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 웹 도메인 소유권 인증 (DAST 스캔 전제조건).
 * 방식: 로그인 사용자별 토큰 발급 → 사용자가 /.well-known/scanops-verify.txt 에 배포 → 백엔드가 fetch 검증.
 * 모든 엔드포인트는 로그인(JWT)이 필요하며, 인증은 (사용자, 도메인) 단위로 분리된다.
 */
@RestController
@RequestMapping("/api/verify")
@RequiredArgsConstructor
public class DomainVerifyController {

    private final DomainVerifyService service;
    private final JwtService jwtService;

    /** 인증 시작 — 토큰과 업로드 경로 발급. */
    @PostMapping("/domain")
    public ResponseEntity<?> init(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, String> body) {
        String ownerId = ownerId(authorization);
        if (ownerId == null) return unauthorized();

        String url = body.getOrDefault("url", "").trim();
        if (url.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "url이 필요합니다"));

        DomainVerification dv = service.initFromUrl(ownerId, url);
        return ResponseEntity.ok(Map.of(
                "domain", dv.getDomain(),
                "token", dv.getVerifyToken(),
                "path", DomainVerifyService.VERIFY_PATH,
                "verified", dv.isVerified()
        ));
    }

    /** 실제 확인 — .well-known 파일을 fetch해 토큰 대조. */
    @PostMapping("/domain/confirm")
    public ResponseEntity<?> confirm(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, String> body) {
        String ownerId = ownerId(authorization);
        if (ownerId == null) return unauthorized();

        String url = body.getOrDefault("url", "").trim();
        if (url.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "url이 필요합니다"));

        boolean verified = service.confirmByFetch(ownerId, url);
        return ResponseEntity.ok(Map.of("verified", verified));
    }

    /** 현재 인증 상태. */
    @GetMapping("/domain")
    public ResponseEntity<?> status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String url) {
        String ownerId = ownerId(authorization);
        if (ownerId == null) return unauthorized();
        return ResponseEntity.ok(Map.of("verified", service.isVerified(ownerId, url)));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** JWT subject(GitHub id). 없거나 유효하지 않으면 null. */
    private String ownerId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        try {
            Claims c = jwtService.parse(authorization.substring(7));
            String sub = c.getSubject();
            return (sub == null || sub.isBlank()) ? null : sub;
        } catch (Exception e) {
            return null;
        }
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));
    }
}

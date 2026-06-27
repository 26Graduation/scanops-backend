package com.scanops.auth;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 로그인된 사용자 정보 조회. 프론트는 OAuth 콜백에서 받은 JWT를
 * `Authorization: Bearer <token>`으로 보내 자신의 프로필을 가져온다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다"));
        }
        try {
            Claims c = jwtService.parse(authorization.substring(7));
            return ResponseEntity.ok(Map.of(
                    "id", c.getSubject(),
                    "githubLogin", c.get("login", String.class),
                    "name", c.get("name", String.class),
                    "email", c.get("email", String.class),
                    "avatarUrl", c.get("avatar", String.class),
                    "plan", c.get("plan", String.class)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "유효하지 않은 토큰입니다"));
        }
    }
}

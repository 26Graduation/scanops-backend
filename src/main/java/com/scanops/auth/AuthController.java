package com.scanops.auth;

import com.scanops.user.User;
import com.scanops.user.UserService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 사용자 로그인/회원가입.
 * - GitHub OAuth 는 {@link OAuth2SuccessHandler} 에서 처리되고 여기선 프로필 조회(/me)만 담당.
 * - 이메일 회원가입/로그인은 여기서 JWT 를 발급한다 (GitHub 과 동일한 토큰 포맷).
 * 프론트는 발급받은 JWT 를 `Authorization: Bearer <token>` 으로 보낸다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;
    private final UserService userService;

    /** 이메일 회원가입 → 즉시 로그인(JWT 발급). */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "").trim();
        String password = body.getOrDefault("password", "");
        String name = body.getOrDefault("name", "");
        if (email.isEmpty() || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "이메일과 비밀번호가 필요합니다"));
        }
        if (password.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "비밀번호는 8자 이상이어야 합니다"));
        }
        try {
            User user = userService.registerEmailUser(email, password, name);
            return ResponseEntity.ok(tokenResponse(user));
        } catch (IllegalStateException e) {
            // 이미 가입된 이메일
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** 이메일 로그인 → JWT 발급. */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "").trim();
        String password = body.getOrDefault("password", "");
        if (email.isEmpty() || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "이메일과 비밀번호가 필요합니다"));
        }
        try {
            User user = userService.authenticateEmail(email, password);
            return ResponseEntity.ok(tokenResponse(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

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

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** User → JWT 발급 후 응답 바디 구성. subject 는 userId(스캔·도메인 인증 스코프 키). */
    private Map<String, Object> tokenResponse(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("login", "");                       // 이메일 가입자는 GitHub 로그인 없음
        claims.put("name", user.getName() != null ? user.getName() : "");
        claims.put("email", user.getEmail() != null ? user.getEmail() : "");
        claims.put("avatar", "");
        claims.put("plan", "FREE");                     // 결제 도입 전 기본 플랜
        String token = jwtService.issue(claims, user.getUserId().toString());
        return Map.of("token", token);
    }
}

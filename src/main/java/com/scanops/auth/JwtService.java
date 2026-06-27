package com.scanops.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * 사용자 로그인 세션용 JWT 발급/검증 (HS256).
 * GitHub App JWT(설치 토큰)와는 무관한 별도의 사용자 인증 토큰이다.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.ttl-hours:168}") long ttlHours) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256은 최소 32바이트 필요 — 짧게 설정해도 죽지 않도록 우측 패딩(운영은 32+ 권장)
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlMillis = ttlHours * 3600_000L;
    }

    /** GitHub 사용자 정보를 담은 JWT 발급. */
    public String issue(Map<String, Object> claims, String subject) {
        Date now = new Date();
        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key)
                .compact();
    }

    /** 검증 후 클레임 반환. 위·만료 시 예외. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}

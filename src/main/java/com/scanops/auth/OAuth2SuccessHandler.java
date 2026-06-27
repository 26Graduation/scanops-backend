package com.scanops.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * GitHub OAuth 로그인 성공 시: GitHub 프로필을 JWT로 만들어
 * 프론트엔드 콜백(`/auth/github/callback?token=...`)으로 리다이렉트한다.
 * (프론트/백엔드 도메인이 달라 세션 쿠키 대신 토큰 전달 방식 사용)
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final JwtService jwtService;
    private final String frontendUrl;

    public OAuth2SuccessHandler(JwtService jwtService,
                                @Value("${app.frontend-url}") String frontendUrl) {
        this.jwtService = jwtService;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User user = (OAuth2User) authentication.getPrincipal();
        String githubId = String.valueOf(user.getAttribute("id"));
        String login = user.getAttribute("login");
        String name = user.getAttribute("name");
        String email = user.getAttribute("email");
        String avatar = user.getAttribute("avatar_url");

        Map<String, Object> claims = new HashMap<>();
        claims.put("login", login);
        claims.put("name", name != null ? name : login);
        claims.put("email", email != null ? email : "");
        claims.put("avatar", avatar != null ? avatar : "");
        claims.put("plan", "FREE"); // 신규 사용자 기본 플랜

        String token = jwtService.issue(claims, githubId);

        String redirect = UriComponentsBuilder
                .fromUriString(frontendUrl + "/auth/github/callback")
                .queryParam("token", token)
                .build().toUriString();
        response.sendRedirect(redirect);
    }
}

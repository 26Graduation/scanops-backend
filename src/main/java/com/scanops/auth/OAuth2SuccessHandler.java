package com.scanops.auth;

import com.scanops.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * GitHub OAuth 로그인 성공 시: GitHub 프로필을 JWT로 만들어
 * 프론트엔드 콜백(`/auth/github/callback?token=...`)으로 리다이렉트한다.
 * (프론트/백엔드 도메인이 달라 세션 쿠키 대신 토큰 전달 방식 사용)
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2SuccessHandler.class);

    private final JwtService jwtService;
    private final UserService userService;
    private final String frontendUrl;

    public OAuth2SuccessHandler(JwtService jwtService, UserService userService,
                                @Value("${app.frontend-url}") String frontendUrl) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2User user = (OAuth2User) authentication.getPrincipal();
            Object idAttr = user.getAttribute("id");
            String githubId = idAttr != null ? String.valueOf(idAttr) : user.getName();
            String login = strAttr(user, "login");
            String name = strAttr(user, "name");
            String email = strAttr(user, "email");
            String avatar = strAttr(user, "avatar_url");

            Map<String, Object> claims = new HashMap<>();
            claims.put("login", login != null ? login : "");
            claims.put("name", name != null ? name : (login != null ? login : "user"));
            claims.put("email", email != null ? email : "");
            claims.put("avatar", avatar != null ? avatar : "");
            claims.put("plan", "FREE"); // 신규 사용자 기본 플랜

            // 결정 6: 로그인 성공 시 users 테이블에 upsert (email 미제공 시 noreply 대체)
            String effectiveEmail = (email != null && !email.isBlank())
                    ? email
                    : (login != null ? login : githubId) + "@users.noreply.github.com";
            userService.upsertGithubUser(effectiveEmail, name != null ? name : login, githubId);

            String token = jwtService.issue(claims, githubId);
            log.info("OAuth login ok: github user {} ({})", login, githubId);

            String redirect = UriComponentsBuilder
                    .fromUriString(frontendUrl + "/auth/github/callback")
                    .queryParam("token", token)
                    .build().toUriString();
            response.sendRedirect(redirect);
        } catch (Exception e) {
            // 무엇이 터지든 Whitelabel 500 대신 프론트로 원인을 들고 리다이렉트
            log.error("OAuth success handler failed", e);
            String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            response.sendRedirect(frontendUrl + "/login?error="
                    + URLEncoder.encode(msg, StandardCharsets.UTF_8));
        }
    }

    /** getAttribute가 String이 아닐 수도 있어 안전하게 문자열화. */
    private static String strAttr(OAuth2User user, String key) {
        Object v = user.getAttribute(key);
        return v == null ? null : String.valueOf(v);
    }
}

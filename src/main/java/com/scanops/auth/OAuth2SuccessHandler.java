package com.scanops.auth;

import com.scanops.user.User;
import com.scanops.user.UserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
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
import java.util.UUID;

/**
 * GitHub OAuth 로그인 성공 시: GitHub 프로필을 JWT로 만들어
 * 프론트엔드 콜백(`/auth/github/callback?token=...`)으로 리다이렉트한다.
 * (프론트/백엔드 도메인이 달라 세션 쿠키 대신 토큰 전달 방식 사용)
 *
 * 두 가지 흐름을 모두 처리한다.
 * 1) 일반 로그인: email 기준으로 users upsert.
 * 2) 계정 연동(link): 이미 이메일로 로그인한 사용자가 GitHub을 붙이는 경우.
 *    {@code scanops_link} 쿠키에 담긴 그 사용자의 JWT로 대상 계정을 식별하고,
 *    그 행에 github_id를 붙인다(= 이메일 계정과 GitHub가 하나의 계정).
 *
 * 어느 경우든 발급 JWT의 subject는 항상 DB 사용자 userId다.
 * (이메일/깃헙 로그인이 같은 사람이면 같은 subject → 도메인 인증 스코프도 일치)
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2SuccessHandler.class);
    static final String LINK_COOKIE = "scanops_link";

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

            // 연동 모드? scanops_link 쿠키(이메일 사용자의 JWT)가 유효하면 그 계정에 붙인다.
            UUID linkUserId = linkTargetUserId(request);
            User account;
            if (linkUserId != null) {
                account = userService.linkGithubToUser(linkUserId, githubId, name);
                clearLinkCookie(response);
                log.info("GitHub linked to account {} (github {}/{})", account.getUserId(), login, githubId);
            } else {
                // 일반 로그인: email 미제공 시 noreply 대체 후 upsert
                String effectiveEmail = (email != null && !email.isBlank())
                        ? email
                        : (login != null ? login : githubId) + "@users.noreply.github.com";
                account = userService.upsertGithubUser(effectiveEmail, name != null ? name : login, githubId);
                log.info("OAuth login ok: github user {} ({}) -> account {}", login, githubId, account.getUserId());
            }

            Map<String, Object> claims = new HashMap<>();
            claims.put("login", login != null ? login : "");
            claims.put("name", account.getName() != null ? account.getName() : (login != null ? login : "user"));
            claims.put("email", account.getEmail() != null ? account.getEmail() : "");
            claims.put("avatar", avatar != null ? avatar : ""); // User엔 미저장 — 세션 표시용
            claims.put("plan", "FREE"); // 결제 도입 전 기본 플랜

            // subject = DB userId (이메일/깃헙 로그인 통합 식별자)
            String token = jwtService.issue(claims, account.getUserId().toString());

            String redirect = UriComponentsBuilder
                    .fromUriString(frontendUrl + "/auth/github/callback")
                    .queryParam("token", token)
                    .build().toUriString();
            response.sendRedirect(redirect);
        } catch (Exception e) {
            // 무엇이 터지든 Whitelabel 500 대신 프론트로 원인을 들고 리다이렉트
            log.error("OAuth success handler failed", e);
            clearLinkCookie(response);
            String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            response.sendRedirect(frontendUrl + "/login?error="
                    + URLEncoder.encode(msg, StandardCharsets.UTF_8));
        }
    }

    /** scanops_link 쿠키의 JWT가 유효하면 대상 userId, 아니면 null(=일반 로그인). */
    private UUID linkTargetUserId(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (!LINK_COOKIE.equals(c.getName())) continue;
            try {
                Claims claims = jwtService.parse(c.getValue());
                String sub = claims.getSubject();
                return (sub == null || sub.isBlank()) ? null : UUID.fromString(sub);
            } catch (Exception e) {
                log.warn("invalid link cookie: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    private void clearLinkCookie(HttpServletResponse response) {
        Cookie c = new Cookie(LINK_COOKIE, "");
        c.setPath("/");
        c.setHttpOnly(true);
        c.setSecure(true);
        c.setMaxAge(0);
        c.setAttribute("SameSite", "Lax");
        response.addCookie(c);
    }

    /** getAttribute가 String이 아닐 수도 있어 안전하게 문자열화. */
    private static String strAttr(OAuth2User user, String key) {
        Object v = user.getAttribute(key);
        return v == null ? null : String.valueOf(v);
    }
}

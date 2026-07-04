package com.scanops.config;

import com.scanops.auth.OAuth2SuccessHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final CorsConfig corsConfig;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final String frontendUrl;

    public SecurityConfig(CorsConfig corsConfig, OAuth2SuccessHandler oAuth2SuccessHandler,
                          @Value("${app.frontend-url}") String frontendUrl) {
        this.corsConfig = corsConfig;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
        this.frontendUrl = frontendUrl;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfig.corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // 스캔 API + 인증 시작/콜백 엔드포인트는 공개 (사용자 식별은 JWT로)
                .requestMatchers("/api/**", "/actuator/health",
                                 "/oauth2/**", "/login/**", "/error").permitAll()
                .anyRequest().authenticated()
            )
            // GitHub OAuth 로그인: 성공 시 JWT 발급 후 프론트로 리다이렉트
            .oauth2Login(oauth -> oauth
                .successHandler(oAuth2SuccessHandler)
                // 실패해도 Whitelabel 500 대신 프론트 로그인 화면으로(원인은 서버 로그에 남김)
                .failureHandler((req, res, ex) -> {
                    log.error("OAuth2 login failed", ex);
                    String url = UriComponentsBuilder.fromUriString(frontendUrl + "/login")
                            .queryParam("error", URLEncoder.encode(ex.getMessage() == null ? "oauth" : ex.getMessage(), StandardCharsets.UTF_8))
                            .build().toUriString();
                    res.sendRedirect(url);
                })
            );
        return http.build();
    }
}

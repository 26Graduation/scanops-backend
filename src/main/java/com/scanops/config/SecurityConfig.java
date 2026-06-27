package com.scanops.config;

import com.scanops.auth.OAuth2SuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CorsConfig corsConfig;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    public SecurityConfig(CorsConfig corsConfig, OAuth2SuccessHandler oAuth2SuccessHandler) {
        this.corsConfig = corsConfig;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
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
            .oauth2Login(oauth -> oauth.successHandler(oAuth2SuccessHandler));
        return http.build();
    }
}

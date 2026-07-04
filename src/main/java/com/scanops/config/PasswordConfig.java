package com.scanops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 인코더 빈을 별도 설정으로 분리.
 * SecurityConfig에 두면 SecurityConfig→OAuth2SuccessHandler→UserService→PasswordEncoder(SecurityConfig)
 * 순환 참조가 생기므로, 의존성 없는 이 config에서 제공한다.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

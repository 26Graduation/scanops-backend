package com.scanops.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** 이메일로 사용자 조회, 없으면 최소 정보로 생성 (스캔 소유자 연결용). */
    @Transactional
    public User findOrCreateByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(User.builder().email(email).build()));
    }

    /** OAuth(GitHub) 로그인 성공 시 프로필로 upsert (결정 6). */
    @Transactional
    public User upsertGithubUser(String email, String name, String githubId) {
        User user = userRepository.findByEmail(email).orElseGet(User::new);
        user.setEmail(email);
        if (name != null) user.setName(name);
        if (githubId != null) user.setGithubId(githubId);
        return userRepository.save(user);
    }

    /**
     * 이메일 회원가입. 이미 존재하는 이메일이면 예외.
     * 비밀번호는 BCrypt 해시로만 저장한다.
     */
    @Transactional
    public User registerEmailUser(String email, String rawPassword, String name) {
        String normalized = normalizeEmail(email);
        if (userRepository.findByEmail(normalized).isPresent()) {
            throw new IllegalStateException("이미 가입된 이메일입니다");
        }
        User user = User.builder()
                .email(normalized)
                .name(name != null && !name.isBlank() ? name : defaultName(normalized))
                .passwordHash(passwordEncoder.encode(rawPassword))
                .build();
        return userRepository.save(user);
    }

    /**
     * 이메일 로그인. 이메일/비밀번호가 맞으면 User 반환.
     * 비밀번호가 없는(=GitHub 전용) 계정이거나 불일치면 예외.
     */
    @Transactional(readOnly = true)
    public User authenticateEmail(String email, String rawPassword) {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다"));
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new IllegalArgumentException("GitHub로 가입한 계정입니다. GitHub로 로그인해 주세요");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다");
        }
        return user;
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String defaultName(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}

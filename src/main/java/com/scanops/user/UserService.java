package com.scanops.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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

    /**
     * OAuth(GitHub) 로그인 성공 시 프로필로 upsert.
     * 계정 탐색 우선순위: (1) github_id → (2) email → (3) 신규.
     * github_id를 먼저 보므로, 다른 이메일로 가입 후 GitHub을 연동한 계정도
     * GitHub 로그인 시 같은 계정으로 이어진다(중복 계정 방지).
     * 기존 계정의 email은 GitHub 이메일로 덮어쓰지 않는다(연동 이메일 보존).
     */
    @Transactional
    public User upsertGithubUser(String email, String name, String githubId) {
        User user = null;
        if (githubId != null && !githubId.isBlank()) {
            user = userRepository.findByGithubId(githubId).orElse(null);
        }
        if (user == null) {
            user = userRepository.findByEmail(email).orElse(null);
        }
        if (user == null) {                 // 신규 계정만 email 설정
            user = new User();
            user.setEmail(email);
        }
        if (name != null && (user.getName() == null || user.getName().isBlank())) {
            user.setName(name);
        }
        if (githubId != null && !githubId.isBlank()) {
            user.setGithubId(githubId);
        }
        return userRepository.save(user);
    }

    /**
     * 이메일 계정에 GitHub을 연동한다(= 하나의 계정).
     * userId 행에 github_id를 붙이고, 같은 github_id를 이미 다른 행이 갖고 있으면
     * 떼어내(중복 방지, 비파괴) 유일성을 유지한다.
     */
    @Transactional
    public User linkGithubToUser(UUID userId, String githubId, String name) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("연동할 계정을 찾을 수 없습니다"));

        if (githubId != null && !githubId.isBlank()) {
            userRepository.findByGithubId(githubId).ifPresent(other -> {
                if (!other.getUserId().equals(userId)) {
                    other.setGithubId(null);       // 이전 GitHub 전용 행에서 분리
                    userRepository.save(other);
                }
            });
            user.setGithubId(githubId);
        }
        if ((user.getName() == null || user.getName().isBlank()) && name != null && !name.isBlank()) {
            user.setName(name);
        }
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

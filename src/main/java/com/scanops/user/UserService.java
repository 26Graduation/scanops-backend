package com.scanops.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

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
}

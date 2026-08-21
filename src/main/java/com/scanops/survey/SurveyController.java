package com.scanops.survey;

import com.scanops.auth.JwtService;
import com.scanops.user.User;
import com.scanops.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 베타테스터 사용경험 설문 참여 여부. 실제 응답 내용은 프론트가 Google Apps Script로
 * 직접 전송하고(개인정보/자유서술 포함), 여기서는 "계정당 1회만" 제약을 위한
 * 참여 여부만 추적한다.
 */
@RestController
@RequestMapping("/api/survey")
@RequiredArgsConstructor
public class SurveyController {

    private final UserRepository userRepository;
    private final JwtService jwtService;

    @GetMapping("/status")
    public ResponseEntity<?> status(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID userId = userId(authorization);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "계정을 찾을 수 없습니다"));

        return ResponseEntity.ok(Map.of(
                "completed", user.getSurveyCompletedAt() != null,
                "completedAt", user.getSurveyCompletedAt() == null ? "" : user.getSurveyCompletedAt().toString()));
    }

    /** 프론트가 Apps Script 전송에 성공한 뒤 호출해 "참여 완료"로 표시한다(멱등). */
    @PostMapping("/complete")
    @Transactional
    public ResponseEntity<?> complete(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID userId = userId(authorization);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "계정을 찾을 수 없습니다"));

        if (user.getSurveyCompletedAt() == null) {
            user.setSurveyCompletedAt(LocalDateTime.now());
            userRepository.save(user);
        }
        return ResponseEntity.ok(Map.of("completed", true, "completedAt", user.getSurveyCompletedAt().toString()));
    }

    private UUID userId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        try {
            Claims c = jwtService.parse(authorization.substring(7));
            return UUID.fromString(c.getSubject());
        } catch (Exception e) {
            return null;
        }
    }
}

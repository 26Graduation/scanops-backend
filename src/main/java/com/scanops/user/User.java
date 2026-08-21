package com.scanops.user;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id")
    private UUID userId;

    private String name;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "github_id")
    private String githubId;   // GitHub 미연동 시 null

    /**
     * 7일 무료체험을 사용한 시각(계정당 1회). null이면 아직 체험 자격이 남아 있다.
     * 구독 행은 해지 후 재가입 시 새로 생기므로 체험 이력은 계정에 남긴다.
     */
    @Column(name = "trial_used_at")
    private LocalDateTime trialUsedAt;

    /** 베타테스터 사용경험 설문 참여 시각(계정당 1회). null이면 아직 참여 안 함. */
    @Column(name = "survey_completed_at")
    private LocalDateTime surveyCompletedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

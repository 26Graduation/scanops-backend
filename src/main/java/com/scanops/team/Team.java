package com.scanops.team;

import com.scanops.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 조직 계정. TEAM 플랜의 결제·토큰 공유 단위다.
 *
 * <p>MVP 단순화로 사용자는 팀을 최대 1개 소유한다({@code owner_user_id UNIQUE}).
 * 팀의 구독(플랜·결제)은 별도 테이블 없이 {@code ownerUser}의 개인 {@code Subscription} 행을
 * 그대로 쓴다 — 그 구독의 plan이 TEAM이면 이 팀의 지갑이 공유 풀로 활성화된다.
 */
@Entity
@Table(name = "teams")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "team_id")
    private UUID teamId;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", nullable = false, unique = true)
    private User ownerUser;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

package com.scanops.team;

import com.scanops.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 팀 소속. {@code user_id}가 UNIQUE라 한 계정은 동시에 한 팀에만 속한다(MVP 단순화) —
 * 스캔 시점에 "이 사용자의 과금 지갑이 개인 것인지 팀 것인지"를 항상 단일 조회로 정할 수 있다.
 */
@Entity
@Table(name = "team_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "team_member_id")
    private UUID teamMemberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TeamRole role;

    @CreationTimestamp
    @Column(name = "joined_at", updatable = false, nullable = false)
    private LocalDateTime joinedAt;
}

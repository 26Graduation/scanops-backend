package com.scanops.subscription;

import com.scanops.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Column(name = "current_period_start")
    private LocalDateTime currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;

    /** 7일 무료체험 종료 시각. PRO 체험이 아니면 항상 null. */
    @Column(name = "trial_end")
    private LocalDateTime trialEnd;

    /** 간편 클릭 해지. true면 현재 주기 종료 시 갱신하지 않는다(기간 내 사용은 유지). */
    @Column(name = "cancel_at_period_end", nullable = false)
    @Builder.Default
    private boolean cancelAtPeriodEnd = false;

    /** 체험 종료 1일 전 알림 발송 시각. 배치가 시간마다 돌아도 중복 발송되지 않도록 막는 용도. */
    @Column(name = "trial_notified_at")
    private LocalDateTime trialNotifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

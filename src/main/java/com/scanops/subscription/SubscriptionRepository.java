package com.scanops.subscription;

import com.scanops.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByUser(User user);
    Optional<Subscription> findByUser_UserId(UUID userId);

    /** 체험 종료 알림/만료 배치용. */
    List<Subscription> findByStatusAndPlan(SubscriptionStatus status, Plan plan);

    /** 주기 갱신 배치용 — 종료 시각이 지난 구독. */
    List<Subscription> findByStatusAndCurrentPeriodEndBefore(SubscriptionStatus status, LocalDateTime at);
}

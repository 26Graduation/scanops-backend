package com.scanops.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UsageMeterRepository extends JpaRepository<UsageMeter, UUID> {
    Optional<UsageMeter> findBySubscription_SubscriptionId(UUID subscriptionId);
}

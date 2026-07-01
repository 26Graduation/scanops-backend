package com.scanops.subscription;

import com.scanops.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByUser(User user);
    Optional<Subscription> findByUser_UserId(UUID userId);
}

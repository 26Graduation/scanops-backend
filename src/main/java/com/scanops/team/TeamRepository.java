package com.scanops.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {
    Optional<Team> findByOwnerUser_UserId(UUID ownerUserId);
}

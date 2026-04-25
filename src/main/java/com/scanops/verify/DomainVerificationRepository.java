package com.scanops.verify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DomainVerificationRepository extends JpaRepository<DomainVerification, UUID> {
    Optional<DomainVerification> findByDomain(String domain);
}

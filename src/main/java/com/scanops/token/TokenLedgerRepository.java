package com.scanops.token;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TokenLedgerRepository extends JpaRepository<TokenLedgerEntry, UUID> {

    Page<TokenLedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    boolean existsByIdempotencyKey(String idempotencyKey);
}

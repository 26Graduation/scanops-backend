package com.scanops.token;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TokenHoldRepository extends JpaRepository<TokenHold, UUID> {

    Optional<TokenHold> findByReferenceKey(String referenceKey);

    Optional<TokenHold> findByScanId(UUID scanId);

    /** 플랜별 동시 실행 제한 확인용. 단위(TOKEN/DAST)별로 따로 센다 — 서로 다른 백엔드 자원이라서. */
    long countByWalletIdAndStatusAndUnit(UUID walletId, HoldStatus status, HoldUnit unit);
}

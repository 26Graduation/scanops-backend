package com.scanops.token;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TokenWalletRepository extends JpaRepository<TokenWallet, UUID> {

    Optional<TokenWallet> findByUser_UserId(UUID userId);

    Optional<TokenWallet> findByTeam_TeamId(UUID teamId);

    /**
     * 잔액 변경 전용 조회. 같은 지갑에 동시에 들어온 스캔이 잔액을 이중으로 쓰지 않도록
     * 행 단위 락(SELECT ... FOR UPDATE)을 잡는다. 반드시 트랜잭션 안에서 호출할 것.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from TokenWallet w where w.user.userId = :userId")
    Optional<TokenWallet> lockByUserId(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from TokenWallet w where w.walletId = :walletId")
    Optional<TokenWallet> lockByWalletId(@Param("walletId") UUID walletId);
}

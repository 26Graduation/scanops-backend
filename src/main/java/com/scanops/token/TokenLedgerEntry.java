package com.scanops.token;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 토큰·DAST 변동 원장 (append-only).
 *
 * <p>잔액의 진실은 {@link TokenWallet}이고, 이 테이블은 "왜 그렇게 됐는지"의 기록이다.
 * 사용자 화면의 사용 내역과 정산 대사(對査)에 그대로 쓴다.
 *
 * <p>{@code amount}는 {@link #unit}에 따라 토큰 또는 DAST 횟수를 뜻한다. 잔액 스냅샷
 * 6개(토큰 3 + DAST 3)는 unit과 무관하게 항상 그 시점의 지갑 전체 상태를 담는다 —
 * 한 화면에서 "이때 전체 잔액이 얼마였는지"를 바로 알 수 있게 하기 위함이다.
 */
@Entity
@Table(name = "token_ledger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "entry_id")
    private UUID entryId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "scan_id")
    private UUID scanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private LedgerEntryType entryType;

    /** 이 항목의 amount 단위. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private HoldUnit unit = HoldUnit.TOKEN;

    /** 지급 +, 소모 -. {@link #unit}에 따라 토큰 또는 DAST 횟수. */
    @Column(nullable = false)
    private long amount;

    @Column(name = "subscription_balance_after", nullable = false)
    private long subscriptionBalanceAfter;

    @Column(name = "purchased_balance_after", nullable = false)
    private long purchasedBalanceAfter;

    @Column(name = "held_balance_after", nullable = false)
    private long heldBalanceAfter;

    @Column(name = "dast_subscription_after", nullable = false)
    private long dastSubscriptionAfter;

    @Column(name = "dast_purchased_after", nullable = false)
    private long dastPurchasedAfter;

    @Column(name = "dast_held_after", nullable = false)
    private long dastHeldAfter;

    @Column(columnDefinition = "TEXT")
    private String memo;

    /** 중복 지급/차감 방지. 웹훅 재전송·배치 재실행에 필수. */
    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}

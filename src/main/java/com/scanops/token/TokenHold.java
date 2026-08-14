package com.scanops.token;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 스캔 1건이 잡아둔 예약 — 토큰(SAST·액션) 또는 DAST 횟수({@link #unit}로 구분).
 *
 * <p>어느 통(구독분/충전분)에서 얼마를 잡았는지 기억해야 정산과 반환이 정확해진다.
 * {@code referenceKey}가 UNIQUE라 같은 스캔/웹훅이 재전송되어도 예약은 1건만 생긴다.
 * DAST 예약은 {@code heldAmount}가 항상 1이다(정액, 정산 시 변동 없음).
 */
@Entity
@Table(name = "token_holds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenHold {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /** 스캔 레코드가 있는 경우(WEBSITE/GITHUB_REPO). PR 스캔은 null. */
    @Column(name = "scan_id")
    private UUID scanId;

    /** 멱등 키. 예: {@code scan:<scanId>}, {@code pr:<repo>#<pr>@<sha>} */
    @Column(name = "reference_key", nullable = false, unique = true)
    private String referenceKey;

    /** 이 예약이 토큰인지 DAST 횟수인지. amount류 컬럼의 단위를 결정한다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private HoldUnit unit = HoldUnit.TOKEN;

    @Column(name = "held_amount", nullable = false)
    private long heldAmount;

    @Column(name = "from_subscription", nullable = false)
    private long fromSubscription;

    @Column(name = "from_purchased", nullable = false)
    private long fromPurchased;

    /** 정산 확정액(실사용). 미정산이면 null. */
    @Column(name = "committed_amount")
    private Long committedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;
}

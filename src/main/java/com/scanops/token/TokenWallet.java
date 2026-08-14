package com.scanops.token;

import com.scanops.team.Team;
import com.scanops.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 사용자별 토큰 지갑.
 *
 * <p>한 지갑 행에 <b>서로 다른 두 통화</b>가 함께 들어 있다.
 * <ul>
 *   <li><b>토큰</b>({@code subscriptionBalance}/{@code purchasedBalance}/{@code heldBalance}):
 *       SAST·GitHub App(액션) 전용. 줄 수에 비례해 차감된다.</li>
 *   <li><b>DAST 횟수</b>({@code dastSubscriptionRemaining}/{@code dastPurchasedRemaining}/
 *       {@code dastHeld}): 웹사이트 점검 전용. 스캔 1건 = 1회, 토큰과 무관하게 따로 차감된다.</li>
 * </ul>
 * 두 통화 모두 구독분(주기마다 리셋)과 충전분(이월)을 분리 보관하고, 진행 중인 스캔이 잡아둔
 * 예약분은 각각의 held로 빠져 있어 동시에 여러 스캔을 걸어도 초과 사용할 수 없다.
 *
 * <p>지갑은 {@code user} 또는 {@code team} 중 정확히 하나에 속한다(DB CHECK 제약으로 강제).
 * TEAM 플랜 멤버 전원은 팀 지갑 하나를 공유해서 쓰고(토큰·DAST 둘 다), 그 외에는 각자 개인
 * 지갑을 쓴다 — 어느 쪽인지는 {@link com.scanops.token.BillingResolver}가 결정한다.
 *
 * <p>잔액 변경은 반드시 {@link TokenService}를 통해 비관적 락 아래에서 한다.
 */
@Entity
@Table(name = "token_wallets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "wallet_id")
    private UUID walletId;

    /** 개인 지갑이면 채워짐. 팀 지갑이면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    /** 팀(공유) 지갑이면 채워짐. 개인 지갑이면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", unique = true)
    private Team team;

    /** 결제주기마다 리셋되는 구독 지급분. 이월 없음. */
    @Column(name = "subscription_balance", nullable = false)
    @Builder.Default
    private long subscriptionBalance = 0;

    /** 5,000원=3,000토큰으로 충전한 분. 이월 O(유효기간 12개월). */
    @Column(name = "purchased_balance", nullable = false)
    @Builder.Default
    private long purchasedBalance = 0;

    /** 진행 중 스캔이 예약(HOLD)한 분. 잔액에서 이미 빠져 있다. */
    @Column(name = "held_balance", nullable = false)
    @Builder.Default
    private long heldBalance = 0;

    /** 결제주기마다 리셋되는 DAST(웹 점검) 지급 횟수. 이월 없음. */
    @Column(name = "dast_subscription_remaining", nullable = false)
    @Builder.Default
    private long dastSubscriptionRemaining = 0;

    /** 가입 보너스·충전으로 받은 DAST 횟수. 이월 O. */
    @Column(name = "dast_purchased_remaining", nullable = false)
    @Builder.Default
    private long dastPurchasedRemaining = 0;

    /** 진행 중 스캔이 예약(HOLD)한 DAST 횟수. */
    @Column(name = "dast_held", nullable = false)
    @Builder.Default
    private long dastHeld = 0;

    /** 가입 보너스(웹 점검 1회)는 계정당 평생 1회. */
    @Column(name = "signup_bonus_granted", nullable = false)
    @Builder.Default
    private boolean signupBonusGranted = false;

    /** 구독분이 만료(리셋)되는 시점. 토큰·DAST 공통 주기. */
    @Column(name = "period_end")
    private LocalDateTime periodEnd;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 지금 새 스캔에 쓸 수 있는 토큰 (예약분 제외). SAST·액션 전용. */
    public long available() {
        return subscriptionBalance + purchasedBalance;
    }

    /** 지금 더 돌릴 수 있는 DAST(웹 점검) 횟수 (예약분 제외). */
    public long dastAvailable() {
        return dastSubscriptionRemaining + dastPurchasedRemaining;
    }
}

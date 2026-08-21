package com.scanops.token;

import com.scanops.subscription.Plan;
import com.scanops.user.User;
import com.scanops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 예약 → 정산 흐름 검증. 리포지토리는 인메모리 스텁으로 대체한다.
 * 토큰(SAST·액션)과 DAST(웹 점검 횟수)는 같은 지갑 행의 서로 다른 통이라, 각각 따로 검증한다.
 */
class TokenServiceTest {

    private TokenWalletRepository walletRepository;
    private TokenHoldRepository holdRepository;
    private TokenLedgerRepository ledgerRepository;
    private TokenService tokenService;

    private User user;
    private TokenWallet wallet;
    private final Map<String, TokenHold> holds = new HashMap<>();

    @BeforeEach
    void setUp() {
        walletRepository = mock(TokenWalletRepository.class);
        holdRepository   = mock(TokenHoldRepository.class);
        ledgerRepository = mock(TokenLedgerRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        tokenService = new TokenService(walletRepository, holdRepository, ledgerRepository, userRepository);

        user = User.builder().userId(UUID.randomUUID()).email("dev@example.com").build();
        wallet = TokenWallet.builder()
                .walletId(UUID.randomUUID())
                .user(user)
                .subscriptionBalance(30_000)   // 임의의 지급량(정산 로직 검증용 — 실제 Pro 월 지급량과 무관)
                .purchasedBalance(1_000)       // 충전분
                .heldBalance(0)
                .dastSubscriptionRemaining(5)  // 임의의 DAST 지급량
                .dastPurchasedRemaining(1)     // 가입 보너스(웹 점검 1회)
                .dastHeld(0)
                .build();

        when(walletRepository.lockByUserId(user.getUserId())).thenReturn(Optional.of(wallet));
        when(walletRepository.lockByWalletId(wallet.getWalletId())).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(TokenWallet.class))).thenAnswer(i -> i.getArgument(0));
        when(ledgerRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(ledgerRepository.save(any(TokenLedgerEntry.class))).thenAnswer(i -> i.getArgument(0));
        when(holdRepository.countByWalletIdAndStatusAndUnit(any(), any(), any())).thenReturn(0L);

        holds.clear();
        when(holdRepository.save(any(TokenHold.class))).thenAnswer(i -> {
            TokenHold h = i.getArgument(0);
            if (h.getHoldId() == null) h.setHoldId(UUID.randomUUID());
            holds.put(h.getReferenceKey(), h);
            return h;
        });
        when(holdRepository.findByReferenceKey(any()))
                .thenAnswer(i -> Optional.ofNullable(holds.get(i.getArgument(0))));
    }

    // ── 토큰(SAST·액션) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰 예약 시 구독분에서 먼저 빠지고 held로 이동한다")
    void holdDeductsSubscriptionFirst() {
        tokenService.hold(wallet, Plan.PRO, HoldUnit.TOKEN, 1_000, "scan:web", null);

        assertEquals(29_000, wallet.getSubscriptionBalance());
        assertEquals(1_000,  wallet.getPurchasedBalance());   // 충전분은 건드리지 않는다
        assertEquals(1_000,  wallet.getHeldBalance());
        assertEquals(30_000, wallet.available());
    }

    @Test
    @DisplayName("실사용이 예약보다 적으면 차액을 돌려준다")
    void commitRefundsUnusedReservation() {
        tokenService.hold(wallet, Plan.PRO, HoldUnit.TOKEN, 3_000, "scan:repo", null);   // 1만 줄 추정
        long committed = tokenService.commit("scan:repo", 900, "소스코드 점검 3,000줄"); // 실제 3천 줄

        assertEquals(900, committed);
        assertEquals(29_100, wallet.getSubscriptionBalance());  // 30,000 - 3,000(예약) + 2,100(반환)
        assertEquals(0, wallet.getHeldBalance());
        assertEquals(30_100, wallet.available());               // 총 31,000 중 900만 소진
    }

    @Test
    @DisplayName("스캔 실패 시 예약 전액이 원래 통으로 돌아간다")
    void releaseRestoresBothBuckets() {
        wallet.setSubscriptionBalance(500);      // 구독분이 모자라 충전분까지 쓰는 상황
        tokenService.hold(wallet, Plan.PRO, HoldUnit.TOKEN, 1_000, "scan:fail", null);
        assertEquals(0, wallet.getSubscriptionBalance());
        assertEquals(500, wallet.getPurchasedBalance());

        tokenService.release("scan:fail", "모델 서버 미응답");

        assertEquals(500,   wallet.getSubscriptionBalance());
        assertEquals(1_000, wallet.getPurchasedBalance());
        assertEquals(0,     wallet.getHeldBalance());
    }

    @Test
    @DisplayName("잔액이 모자라면 예약이 거부된다")
    void holdRejectsWhenInsufficient() {
        wallet.setSubscriptionBalance(0);
        wallet.setPurchasedBalance(900);   // 1,000 필요한데 100 부족

        InsufficientTokensException e = assertThrows(InsufficientTokensException.class,
                () -> tokenService.hold(wallet, Plan.PRO, HoldUnit.TOKEN, 1_000, "scan:poor", null));

        assertEquals(1_000, e.getRequired());
        assertEquals(900,   e.getAvailable());
        assertEquals(900,   wallet.available());   // 잔액은 그대로
    }

    @Test
    @DisplayName("같은 키로 다시 예약해도 이중 차감되지 않는다 (웹훅 재전송)")
    void holdIsIdempotent() {
        tokenService.hold(wallet, Plan.PRO, HoldUnit.TOKEN, 1_000, "pr:acme/api#7@abc", null);
        tokenService.hold(wallet, Plan.PRO, HoldUnit.TOKEN, 1_000, "pr:acme/api#7@abc", null);

        assertEquals(29_000, wallet.getSubscriptionBalance());
        assertEquals(1_000,  wallet.getHeldBalance());
    }

    @Test
    @DisplayName("정산도 멱등 — 두 번 불러도 한 번만 차감된다")
    void commitIsIdempotent() {
        tokenService.hold(wallet, Plan.PRO, HoldUnit.TOKEN, 1_000, "scan:web", null);
        tokenService.commit("scan:web", 1_000, "웹 점검");
        tokenService.commit("scan:web", 1_000, "웹 점검");

        assertEquals(29_000, wallet.getSubscriptionBalance());
        assertEquals(0, wallet.getHeldBalance());
        assertEquals(30_000, wallet.available());
    }

    @Test
    @DisplayName("추정보다 훨씬 큰 레포여도 잔액이 음수가 되지 않는다")
    void commitNeverGoesNegative() {
        wallet.setSubscriptionBalance(300);
        wallet.setPurchasedBalance(0);

        tokenService.hold(wallet, Plan.PRO, HoldUnit.TOKEN, 300, "scan:huge", null);   // 최소 과금으로 예약
        long committed = tokenService.commit("scan:huge", 9_000, "소스코드 점검 30,000줄");

        assertEquals(300, committed);          // 잡아둔 만큼만 확정
        assertEquals(0, wallet.available());
        assertEquals(0, wallet.getHeldBalance());
    }

    @Test
    @DisplayName("Pro는 SAST 동시 스캔 2개까지 — 세 번째 요청은 거부된다")
    void concurrentScanLimit() {
        when(holdRepository.countByWalletIdAndStatusAndUnit(any(), eq(HoldStatus.HELD), eq(HoldUnit.TOKEN)))
                .thenReturn((long) Plan.PRO.maxConcurrentSastScans());

        assertThrows(ConcurrentScanLimitException.class,
                () -> tokenService.hold(wallet, Plan.PRO, HoldUnit.TOKEN, 1_000, "scan:second", null));
    }

    // ── DAST(웹 점검 횟수) — 토큰과 완전히 별개인 통 ─────────────────────────

    @Test
    @DisplayName("DAST 예약은 토큰 잔액을 전혀 건드리지 않는다")
    void dastHoldDoesNotTouchTokens() {
        tokenService.hold(wallet, Plan.PRO, HoldUnit.DAST, 1, "scan:dast", null);

        assertEquals(30_000, wallet.getSubscriptionBalance());   // 토큰 그대로
        assertEquals(4, wallet.getDastSubscriptionRemaining());  // 5 - 1
        assertEquals(1, wallet.getDastPurchasedRemaining());     // 보너스는 그대로(구독분부터 소진)
        assertEquals(1, wallet.getDastHeld());
    }

    @Test
    @DisplayName("DAST 정액 정산 — 예약 1회가 그대로 확정된다")
    void dastCommitIsFlat() {
        tokenService.hold(wallet, Plan.PRO, HoldUnit.DAST, 1, "scan:dast", null);
        long committed = tokenService.commit("scan:dast", 1, "웹사이트 점검 1회");

        assertEquals(1, committed);
        assertEquals(0, wallet.getDastHeld());
        assertEquals(5, wallet.dastAvailable());   // 4(구독) + 1(보너스)
    }

    @Test
    @DisplayName("DAST 실패 시 횟수가 그대로 반환된다")
    void dastReleaseRestoresCount() {
        wallet.setDastSubscriptionRemaining(0);
        tokenService.hold(wallet, Plan.PRO, HoldUnit.DAST, 1, "scan:dast-fail", null);
        assertEquals(0, wallet.getDastPurchasedRemaining());   // 구독분 0이라 보너스에서 빠짐

        tokenService.release("scan:dast-fail", "ZAP 서버 미응답");

        assertEquals(0, wallet.getDastSubscriptionRemaining());
        assertEquals(1, wallet.getDastPurchasedRemaining());
        assertEquals(0, wallet.getDastHeld());
    }

    @Test
    @DisplayName("DAST 잔여 횟수가 0이면 토큰이 남아 있어도 예약이 거부된다")
    void dastHoldRejectsWhenCountExhausted() {
        wallet.setDastSubscriptionRemaining(0);
        wallet.setDastPurchasedRemaining(0);

        assertThrows(InsufficientTokensException.class,
                () -> tokenService.hold(wallet, Plan.PRO, HoldUnit.DAST, 1, "scan:no-dast", null));

        assertEquals(30_000, wallet.getSubscriptionBalance());   // 토큰은 여전히 넉넉함 — 별개 통이라 무관
    }

    @Test
    @DisplayName("DAST와 토큰의 동시 실행 카운트는 서로 독립적이다")
    void dastAndTokenConcurrencyAreIndependent() {
        // 토큰 스캔이 이미 1개 진행 중이어도 DAST 예약(Pro 동시 1개 한도)은 영향받지 않는다.
        when(holdRepository.countByWalletIdAndStatusAndUnit(any(), eq(HoldStatus.HELD), eq(HoldUnit.TOKEN)))
                .thenReturn(1L);
        when(holdRepository.countByWalletIdAndStatusAndUnit(any(), eq(HoldStatus.HELD), eq(HoldUnit.DAST)))
                .thenReturn(0L);

        assertDoesNotThrow(() -> tokenService.hold(wallet, Plan.PRO, HoldUnit.DAST, 1, "scan:dast-ok", null));
    }
}

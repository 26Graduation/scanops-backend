package com.scanops.token;

import com.scanops.subscription.Plan;
import com.scanops.team.Team;
import com.scanops.user.User;
import com.scanops.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 토큰(SAST·GitHub App) + DAST(웹 점검 횟수) 지급·차감 엔진.
 *
 * <p>한 지갑 안에 서로 다른 두 통화가 있다({@link TokenWallet} 문서 참고). 예약(hold)·정산
 * (commit)·반환(release)은 {@link HoldUnit}으로 어느 통화인지 받아 같은 로직을 공유한다 —
 * DAST는 항상 amount=1이고 정산 시 변동이 없다는 점만 다르다.
 *
 * <pre>
 *   hold(예상치, unit) → 잔액에서 빼서 held로 옮김
 *      ├─ commit(실사용) → 차액 반환, 실사용분만 확정 차감 (DAST는 항상 예약=확정)
 *      └─ release()      → 전액 반환 (실패·무과금)
 * </pre>
 *
 * <p>모든 잔액 변경은 지갑 행에 비관적 락을 잡고 수행하며, 원장에 append-only로 남긴다.
 * {@code referenceKey}가 UNIQUE라 웹훅 재전송 같은 중복 요청은 기존 예약을 그대로 돌려준다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    private final TokenWalletRepository walletRepository;
    private final TokenHoldRepository holdRepository;
    private final TokenLedgerRepository ledgerRepository;
    private final UserRepository userRepository;

    // ── 지갑 ────────────────────────────────────────────────────────────────

    /**
     * 지갑 조회(없으면 생성). 신규 계정이면 가입 보너스(웹 점검 1회, DAST 횟수)를 함께 지급한다.
     * 보너스는 {@code signup_bonus_granted} 플래그로 계정당 1회만 나간다.
     */
    @Transactional
    public TokenWallet getOrCreateWallet(User user) {
        Optional<TokenWallet> existing = walletRepository.findByUser_UserId(user.getUserId());
        if (existing.isPresent()) return existing.get();

        TokenWallet wallet = walletRepository.save(TokenWallet.builder().user(user).build());
        grantSignupBonus(wallet);
        return wallet;
    }

    @Transactional(readOnly = true)
    public Optional<TokenWallet> findWallet(UUID userId) {
        return walletRepository.findByUser_UserId(userId);
    }

    /**
     * 팀 공유 지갑 조회(없으면 생성). 팀 지갑에는 가입 보너스가 없다 —
     * 보너스는 계정 단위 혜택이라 개인 지갑에서만 나간다.
     */
    @Transactional
    public TokenWallet getOrCreateTeamWallet(Team team) {
        return walletRepository.findByTeam_TeamId(team.getTeamId())
                .orElseGet(() -> walletRepository.save(TokenWallet.builder().team(team).build()));
    }

    /** 사용 내역(원장) 조회 — 마이페이지용. 토큰·DAST 항목이 함께 최신순으로 섞여 나온다. */
    @Transactional(readOnly = true)
    public Page<TokenLedgerEntry> ledger(UUID walletId, Pageable pageable) {
        return ledgerRepository.findByWalletIdOrderByCreatedAtDesc(walletId, pageable);
    }

    // ── 지급 ────────────────────────────────────────────────────────────────

    /** 가입 보너스 = 웹사이트 점검 1회(DAST 횟수, 토큰 아님). 플랜과 무관하게 계정당 평생 1회. */
    @Transactional
    public void grantSignupBonus(TokenWallet wallet) {
        TokenWallet locked = lock(wallet.getWalletId());
        if (locked.isSignupBonusGranted()) return;

        locked.setDastPurchasedRemaining(locked.getDastPurchasedRemaining() + TokenPolicy.SIGNUP_BONUS_DAST_COUNT);
        locked.setSignupBonusGranted(true);
        walletRepository.save(locked);

        writeLedger(locked, LedgerEntryType.SIGNUP_BONUS, HoldUnit.DAST, TokenPolicy.SIGNUP_BONUS_DAST_COUNT,
                null, "가입 보너스 — 웹사이트 점검 1회",
                "signup-bonus:" + locked.getUser().getUserId());
    }

    /**
     * 7일 무료체험 지급. PRO 전용이고 월 지급량의 25%만 준다(토큰·DAST 둘 다 비례).
     * 계정당 1회 — {@code users.trial_used_at}으로 막는다(해지 후 재가입해도 부활하지 않음).
     *
     * @return 지급했으면 true, 이미 체험을 썼거나 체험 대상 플랜이 아니면 false
     */
    @Transactional
    public boolean grantTrial(User user, Plan plan) {
        if (!plan.trialEligible()) {
            log.info("[토큰] {} 플랜은 무료체험 대상이 아님 (PRO 전용)", plan);
            return false;
        }
        User owner = userRepository.findById(user.getUserId()).orElseThrow();
        if (owner.getTrialUsedAt() != null) {
            log.info("[토큰] 이미 체험을 사용한 계정: {}", owner.getUserId());
            return false;
        }

        TokenWallet wallet = lockByUser(owner);
        wallet.setSubscriptionBalance(wallet.getSubscriptionBalance() + plan.trialTokens());
        wallet.setDastSubscriptionRemaining(wallet.getDastSubscriptionRemaining() + plan.trialDastCount());
        wallet.setPeriodEnd(LocalDateTime.now().plusDays(TokenPolicy.TRIAL_DAYS));
        walletRepository.save(wallet);

        owner.setTrialUsedAt(LocalDateTime.now());
        userRepository.save(owner);

        String memo = TokenPolicy.TRIAL_DAYS + "일 무료체험 지급 (" + plan + " 월 지급량의 25%)";
        String key = "trial:" + owner.getUserId();
        if (plan.trialTokens() > 0) {
            writeLedger(wallet, LedgerEntryType.TRIAL_GRANT, HoldUnit.TOKEN, plan.trialTokens(), null, memo, key);
        }
        if (plan.trialDastCount() > 0) {
            writeLedger(wallet, LedgerEntryType.TRIAL_GRANT, HoldUnit.DAST, plan.trialDastCount(), null, memo, key + ":dast");
        }
        return true;
    }

    /**
     * 결제주기 지급(개인 지갑) — 토큰(SAST·액션)과 DAST 횟수를 함께 갱신한다.
     * 이월이 없으므로 기존 구독분은 <b>덮어쓴다</b>(누적 X). 체험 잔여분도 이 시점에 소멸한다.
     *
     * @param idempotencyKey 주기별 고유 키. 배치 재실행 시 이중 지급을 막는다.
     */
    @Transactional
    public void grantPeriodTokens(User user, Plan plan, int seats,
                                  LocalDateTime periodEnd, String idempotencyKey) {
        if (idempotencyKey != null && ledgerRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.info("[토큰] 이미 지급된 주기: {}", idempotencyKey);
            return;
        }
        grantPeriodLocked(lockByUser(user), plan, seats, periodEnd, idempotencyKey);
    }

    /**
     * 결제주기 지급(팀 공유 지갑). TEAM 플랜 활성화 시 팀장의 개인 지갑이 아니라
     * 이 지갑으로 들어가야 멤버 전원이 공유해서 쓸 수 있다.
     */
    @Transactional
    public void grantPeriodTokens(TokenWallet teamWallet, Plan plan, int seats,
                                  LocalDateTime periodEnd, String idempotencyKey) {
        if (idempotencyKey != null && ledgerRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.info("[토큰] 이미 지급된 주기: {}", idempotencyKey);
            return;
        }
        grantPeriodLocked(lock(teamWallet.getWalletId()), plan, seats, periodEnd, idempotencyKey);
    }

    private void grantPeriodLocked(TokenWallet wallet, Plan plan, int seats,
                                   LocalDateTime periodEnd, String idempotencyKey) {
        long tokenGrant = plan.grantForSeats(seats);
        int dastGrant = plan.dastLimitForSeats(seats);

        long expiredTokens = wallet.getSubscriptionBalance();
        long expiredDast = wallet.getDastSubscriptionRemaining();

        wallet.setSubscriptionBalance(tokenGrant);
        wallet.setDastSubscriptionRemaining(dastGrant);
        wallet.setPeriodEnd(periodEnd);
        walletRepository.save(wallet);

        if (expiredTokens > 0) {
            writeLedger(wallet, LedgerEntryType.EXPIRE, HoldUnit.TOKEN, -expiredTokens, null,
                    "주기 종료로 구독 토큰 소멸 (이월 없음)", null);
        }
        if (expiredDast > 0) {
            writeLedger(wallet, LedgerEntryType.EXPIRE, HoldUnit.DAST, -expiredDast, null,
                    "주기 종료로 DAST 잔여 횟수 소멸 (이월 없음)", null);
        }
        String seatNote = plan == Plan.TEAM ? " (" + seats + "인 공유 풀)" : "";
        writeLedger(wallet, LedgerEntryType.GRANT, HoldUnit.TOKEN, tokenGrant, null,
                plan + " 구독 토큰 지급" + seatNote, idempotencyKey);
        writeLedger(wallet, LedgerEntryType.GRANT, HoldUnit.DAST, dastGrant, null,
                plan + " DAST 점검 횟수 지급" + seatNote,
                idempotencyKey == null ? null : idempotencyKey + ":dast");
    }

    /** 초과분 토큰 충전(SAST·액션 전용). 5,000원 = 3,000토큰. 충전분은 이월된다. */
    @Transactional
    public TokenWallet purchase(User user, int units, String paymentId) {
        long amount = TokenPolicy.PURCHASE_TOKENS * units;
        TokenWallet wallet = lockByUser(user);
        wallet.setPurchasedBalance(wallet.getPurchasedBalance() + amount);
        walletRepository.save(wallet);

        writeLedger(wallet, LedgerEntryType.PURCHASE, HoldUnit.TOKEN, amount, null,
                String.format("토큰 충전 %,d원 (%d구좌)", TokenPolicy.PURCHASE_PRICE_KRW * units, units),
                paymentId == null ? null : "purchase:" + paymentId);
        return wallet;
    }

    /** DAST 횟수 충전. 5,000원 = 3회. 충전분은 이월된다. */
    @Transactional
    public TokenWallet purchaseDast(User user, int units, String paymentId) {
        long count = (long) TokenPolicy.DAST_PURCHASE_COUNT * units;
        TokenWallet wallet = lockByUser(user);
        wallet.setDastPurchasedRemaining(wallet.getDastPurchasedRemaining() + count);
        walletRepository.save(wallet);

        writeLedger(wallet, LedgerEntryType.PURCHASE, HoldUnit.DAST, count, null,
                String.format("DAST 충전 %,d원 (%d구좌)", TokenPolicy.DAST_PURCHASE_PRICE_KRW * units, units),
                paymentId == null ? null : "purchase-dast:" + paymentId);
        return wallet;
    }

    // ── 예약 → 정산 ─────────────────────────────────────────────────────────

    /**
     * 스캔 시작 전 예상 비용을 예약한다. {@code unit}에 따라 토큰(SAST·액션) 또는
     * DAST 횟수(웹 점검, amount는 항상 1)를 차감한다.
     *
     * <p>어느 지갑에서 뺄지는 호출부가 {@link BillingResolver}로 미리 정해서 넘긴다 —
     * 개인 스캔이면 본인 지갑, 팀 멤버의 TEAM 플랜 스캔이면 팀 공유 지갑.
     * 넘어온 {@code walletRef}는 참조용이고, 실제 잔액은 여기서 다시 잠가(재조회) 확인한다.
     *
     * @param referenceKey 스캔당 고유 키({@code scan:<id>}, {@code pr:<repo>#<pr>@<sha>}).
     *                     같은 키로 다시 부르면 기존 예약을 그대로 반환한다(멱등).
     * @throws InsufficientTokensException   잔액 부족
     * @throws ConcurrentScanLimitException  플랜 동시 실행 한도 초과
     */
    @Transactional
    public TokenHold hold(TokenWallet walletRef, Plan plan, HoldUnit unit, long amount,
                          String referenceKey, UUID scanId) {
        Optional<TokenHold> existing = holdRepository.findByReferenceKey(referenceKey);
        if (existing.isPresent()) {
            log.info("[{}] 중복 예약 요청 — 기존 예약 재사용: {}", unit, referenceKey);
            return existing.get();
        }

        TokenWallet wallet = lock(walletRef.getWalletId());

        int concurrentLimit = unit == HoldUnit.TOKEN ? plan.maxConcurrentSastScans() : plan.maxConcurrentDastScans();
        long running = holdRepository.countByWalletIdAndStatusAndUnit(wallet.getWalletId(), HoldStatus.HELD, unit);
        if (running >= concurrentLimit) {
            throw new ConcurrentScanLimitException(concurrentLimit);
        }
        long available = unit == HoldUnit.TOKEN ? wallet.available() : wallet.dastAvailable();
        if (available < amount) {
            throw new InsufficientTokensException(amount, available);
        }

        // 구독분 → 충전분 순서로 뺀다(이월되는 충전분을 남겨두는 쪽이 사용자에게 유리).
        long sub = unit == HoldUnit.TOKEN ? wallet.getSubscriptionBalance() : wallet.getDastSubscriptionRemaining();
        long pur = unit == HoldUnit.TOKEN ? wallet.getPurchasedBalance() : wallet.getDastPurchasedRemaining();
        long fromSub = Math.min(sub, amount);
        long fromPur = amount - fromSub;

        setSub(wallet, unit, sub - fromSub);
        setPur(wallet, unit, pur - fromPur);
        setHeld(wallet, unit, getHeld(wallet, unit) + amount);
        walletRepository.save(wallet);

        TokenHold heldRecord = holdRepository.save(TokenHold.builder()
                .walletId(wallet.getWalletId())
                .scanId(scanId)
                .referenceKey(referenceKey)
                .unit(unit)
                .heldAmount(amount)
                .fromSubscription(fromSub)
                .fromPurchased(fromPur)
                .status(HoldStatus.HELD)
                .build());

        String label = unit == HoldUnit.TOKEN ? "토큰" : "DAST 횟수";
        writeLedger(wallet, LedgerEntryType.HOLD, unit, -amount, scanId,
                String.format("스캔 예약 %,d %s", amount, label), "hold:" + referenceKey);
        return heldRecord;
    }

    /**
     * 실사용량으로 정산한다. 예약보다 적게 썼으면 차액을 돌려주고,
     * 많이 썼으면 잔액 범위 안에서 추가 차감한다(잔액이 모자라면 그만큼만 — 마이너스가 되지 않는다).
     * DAST 예약(unit=DAST)은 항상 {@code actualAmount}가 예약과 같다(변동 없는 정액).
     *
     * @return 최종 확정 차감액
     */
    @Transactional
    public long commit(String referenceKey, long actualAmount, String memo) {
        TokenHold hold = holdRepository.findByReferenceKey(referenceKey).orElse(null);
        if (hold == null) {
            log.info("[토큰] 예약 없이 완료된 스캔(무과금 대상): {}", referenceKey);
            return 0;
        }
        if (hold.getStatus() != HoldStatus.HELD) {
            log.info("[토큰] 이미 정산된 예약: {} ({})", referenceKey, hold.getStatus());
            return hold.getCommittedAmount() == null ? 0 : hold.getCommittedAmount();
        }

        HoldUnit unit = hold.getUnit();
        long actual = Math.max(0, actualAmount);
        TokenWallet wallet = lock(hold.getWalletId());

        long confirmed = Math.min(actual, hold.getHeldAmount());
        long refund = hold.getHeldAmount() - confirmed;

        // 반환은 충전분부터 되돌린다 → 결과적으로 구독분이 먼저 소진되는 순서가 유지된다.
        long refundPur = Math.min(refund, hold.getFromPurchased());
        long refundSub = refund - refundPur;

        long extra = actual - hold.getHeldAmount();
        long extraTaken = 0;
        if (extra > 0) {
            long available = unit == HoldUnit.TOKEN ? wallet.available() : wallet.dastAvailable();
            extraTaken = Math.min(extra, available);
            long sub = getSub(wallet, unit);
            long extraSub = Math.min(sub, extraTaken);
            long extraPur = extraTaken - extraSub;
            setSub(wallet, unit, sub - extraSub);
            setPur(wallet, unit, getPur(wallet, unit) - extraPur);
            if (extraTaken < extra) {
                log.warn("[{}] 예약 초과분 {} 중 {}만 차감(잔액 부족): {}", unit, extra, extraTaken, referenceKey);
            }
        }

        setSub(wallet, unit, getSub(wallet, unit) + refundSub);
        setPur(wallet, unit, getPur(wallet, unit) + refundPur);
        setHeld(wallet, unit, getHeld(wallet, unit) - hold.getHeldAmount());
        walletRepository.save(wallet);

        long committed = confirmed + extraTaken;
        hold.setCommittedAmount(committed);
        hold.setStatus(HoldStatus.COMMITTED);
        hold.setSettledAt(LocalDateTime.now());
        holdRepository.save(hold);

        String label = unit == HoldUnit.TOKEN ? "토큰" : "DAST 횟수";
        // 원장 amount = 사용 가능 잔액의 순변화(반환 +, 추가차감 -)
        writeLedger(wallet, LedgerEntryType.COMMIT, unit, refund - extraTaken, hold.getScanId(),
                String.format("%s — 예약 %,d → 확정 %,d %s",
                        memo == null ? "스캔 정산" : memo, hold.getHeldAmount(), committed, label),
                "commit:" + referenceKey);
        return committed;
    }

    /** 스캔 실패·무과금 → 예약 전액 반환. 이미 정산된 예약이면 아무것도 하지 않는다. */
    @Transactional
    public void release(String referenceKey, String reason) {
        TokenHold hold = holdRepository.findByReferenceKey(referenceKey).orElse(null);
        if (hold == null || hold.getStatus() != HoldStatus.HELD) return;

        HoldUnit unit = hold.getUnit();
        TokenWallet wallet = lock(hold.getWalletId());
        setSub(wallet, unit, getSub(wallet, unit) + hold.getFromSubscription());
        setPur(wallet, unit, getPur(wallet, unit) + hold.getFromPurchased());
        setHeld(wallet, unit, getHeld(wallet, unit) - hold.getHeldAmount());
        walletRepository.save(wallet);

        hold.setCommittedAmount(0L);
        hold.setStatus(HoldStatus.RELEASED);
        hold.setSettledAt(LocalDateTime.now());
        holdRepository.save(hold);

        String label = unit == HoldUnit.TOKEN ? "토큰" : "DAST 횟수";
        writeLedger(wallet, LedgerEntryType.RELEASE, unit, hold.getHeldAmount(), hold.getScanId(),
                String.format("예약 %,d %s 반환 — %s", hold.getHeldAmount(), label,
                        reason == null ? "스캔 미과금" : reason),
                "release:" + referenceKey);
    }

    /** 스캔 ID로 정산(파이프라인에서 referenceKey를 다시 만들지 않아도 되도록). */
    public static String scanKey(UUID scanId) {
        return "scan:" + scanId;
    }

    // ── 내부: 단위(TOKEN/DAST)에 따라 지갑의 sub/pur/held 필드를 골라 쓰는 헬퍼 ──────

    private long getSub(TokenWallet w, HoldUnit u) {
        return u == HoldUnit.TOKEN ? w.getSubscriptionBalance() : w.getDastSubscriptionRemaining();
    }

    private void setSub(TokenWallet w, HoldUnit u, long v) {
        if (u == HoldUnit.TOKEN) w.setSubscriptionBalance(v); else w.setDastSubscriptionRemaining(v);
    }

    private long getPur(TokenWallet w, HoldUnit u) {
        return u == HoldUnit.TOKEN ? w.getPurchasedBalance() : w.getDastPurchasedRemaining();
    }

    private void setPur(TokenWallet w, HoldUnit u, long v) {
        if (u == HoldUnit.TOKEN) w.setPurchasedBalance(v); else w.setDastPurchasedRemaining(v);
    }

    private long getHeld(TokenWallet w, HoldUnit u) {
        return u == HoldUnit.TOKEN ? w.getHeldBalance() : w.getDastHeld();
    }

    private void setHeld(TokenWallet w, HoldUnit u, long v) {
        if (u == HoldUnit.TOKEN) w.setHeldBalance(v); else w.setDastHeld(v);
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private TokenWallet lockByUser(User user) {
        return walletRepository.lockByUserId(user.getUserId())
                .orElseGet(() -> {
                    TokenWallet created = walletRepository.saveAndFlush(
                            TokenWallet.builder().user(user).build());
                    return walletRepository.lockByWalletId(created.getWalletId()).orElse(created);
                });
    }

    private TokenWallet lock(UUID walletId) {
        return walletRepository.lockByWalletId(walletId)
                .orElseThrow(() -> new IllegalStateException("지갑을 찾을 수 없습니다: " + walletId));
    }

    /**
     * 원장 기록. {@code idempotencyKey}가 이미 있으면 건너뛴다.
     * 호출부의 트랜잭션 안에서만 불린다 — 지갑 갱신과 원자적으로 커밋되므로
     * 원장만 남고 잔액이 안 바뀌는 상황은 생기지 않는다. 토큰·DAST 잔액을 항상 함께 스냅샷한다.
     */
    private void writeLedger(TokenWallet wallet, LedgerEntryType type, HoldUnit unit, long amount,
                             UUID scanId, String memo, String idempotencyKey) {
        if (idempotencyKey != null && ledgerRepository.existsByIdempotencyKey(idempotencyKey)) return;

        ledgerRepository.save(TokenLedgerEntry.builder()
                .walletId(wallet.getWalletId())
                .scanId(scanId)
                .entryType(type)
                .unit(unit)
                .amount(amount)
                .subscriptionBalanceAfter(wallet.getSubscriptionBalance())
                .purchasedBalanceAfter(wallet.getPurchasedBalance())
                .heldBalanceAfter(wallet.getHeldBalance())
                .dastSubscriptionAfter(wallet.getDastSubscriptionRemaining())
                .dastPurchasedAfter(wallet.getDastPurchasedRemaining())
                .dastHeldAfter(wallet.getDastHeld())
                .memo(memo)
                .idempotencyKey(idempotencyKey)
                .build());
    }
}

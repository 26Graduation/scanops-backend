package com.scanops.subscription;

import com.scanops.team.Team;
import com.scanops.team.TeamMemberRepository;
import com.scanops.team.TeamRepository;
import com.scanops.token.TokenPolicy;
import com.scanops.token.TokenService;
import com.scanops.token.TokenWallet;
import com.scanops.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 구독 생명주기 — 체험 시작, 결제 전환, 해지, 주기 갱신.
 *
 * <p>무료체험은 <b>PRO 전용</b> 7일이다. MAX/TEAM은 가입 즉시 결제되고 체험이 없다.
 * 체험 지급량은 월 지급량의 25%(12,500토큰)이며, 유료 전환 시 체험 잔여분은 소멸하고
 * 월 지급량이 새로 지급된다({@link TokenService#grantPeriodTokens}가 덮어쓰기 방식).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final TokenService tokenService;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;

    /**
     * 과금에 쓸 현재 플랜. 구독이 없거나 만료/연체면 FREE로 떨어진다.
     * FREE도 가입 보너스(DAST 1회)가 남아 있으면 웹 점검은 가능하다.
     */
    @Transactional(readOnly = true)
    public Plan currentPlan(UUID userId) {
        return subscriptionRepository.findByUser_UserId(userId)
                .filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE
                          || s.getStatus() == SubscriptionStatus.TRIALING)
                .map(Subscription::getPlan)
                .orElse(Plan.FREE);
    }

    /**
     * 7일 무료체험 시작. PRO만 가능하고, 계정당 1회다(재가입해도 부활하지 않음).
     *
     * @throws IllegalArgumentException PRO가 아닌 플랜으로 체험을 시도한 경우
     * @throws IllegalStateException    이미 체험을 사용한 계정
     */
    @Transactional
    public Subscription startTrial(User user, Plan plan) {
        if (!plan.trialEligible()) {
            throw new IllegalArgumentException(
                    "무료체험은 Pro 플랜에서만 가능합니다. " + plan + " 플랜은 가입 즉시 결제됩니다.");
        }
        if (!tokenService.grantTrial(user, plan)) {
            throw new IllegalStateException("이미 무료체험을 사용한 계정입니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime trialEnd = now.plusDays(TokenPolicy.TRIAL_DAYS);

        Subscription subscription = subscriptionRepository.findByUser(user)
                .orElseGet(() -> Subscription.builder().user(user).build());
        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.TRIALING);
        subscription.setCurrentPeriodStart(now);
        subscription.setCurrentPeriodEnd(trialEnd);
        subscription.setTrialEnd(trialEnd);
        subscription.setCancelAtPeriodEnd(false);
        subscription.setTrialNotifiedAt(null);

        log.info("[구독] {} 체험 시작 — 종료 {}", user.getUserId(), trialEnd);
        return subscriptionRepository.save(subscription);
    }

    /**
     * 유료 구독 시작/전환(결제 성공 후 호출). 체험 중이라면 그 시점에 체험이 끝나고
     * 잔여 체험 토큰은 소멸한다.
     *
     * <p>TEAM 플랜은 지급이 팀 공유 지갑으로 들어간다 — 활성화하려는 사용자가 미리
     * {@code POST /api/teams}로 팀을 만들어 놓아야 한다({@link TeamRepository#findByOwnerUser_UserId}로
     * 찾는다). 인원 수는 요청 값이 아니라 <b>실제 팀원 수</b>로 다시 계산한다(클라이언트가
     * seats를 조작해 토큰을 더 받는 것을 막기 위함) — 기본 3인까지는 정액, 초과분만 인당 추가 지급.
     *
     * @param seats TEAM 외 플랜에서는 무시된다(개인 플랜은 인원 개념이 없음).
     * @throws IllegalStateException TEAM 활성화인데 사용자가 팀장이 아닌 경우
     */
    @Transactional
    public Subscription activate(User user, Plan plan, int seats, String paymentId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodEnd = now.plusMonths(1);

        Subscription subscription = subscriptionRepository.findByUser(user)
                .orElseGet(() -> Subscription.builder().user(user).build());
        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodStart(now);
        subscription.setCurrentPeriodEnd(periodEnd);
        subscription.setTrialEnd(null);
        subscription.setCancelAtPeriodEnd(false);
        Subscription saved = subscriptionRepository.save(subscription);

        // 주기별 고유 키 — 결제 재시도/배치 재실행에도 이중 지급되지 않는다.
        String key = "grant:" + saved.getSubscriptionId() + ":"
                + (paymentId != null ? paymentId : periodEnd.toLocalDate());

        if (plan == Plan.TEAM) {
            Team team = teamRepository.findByOwnerUser_UserId(user.getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "TEAM 플랜은 팀을 먼저 만들어야 활성화할 수 있습니다. POST /api/teams로 팀을 생성해 주세요."));
            int actualSeats = (int) teamMemberRepository.countByTeam_TeamId(team.getTeamId());
            TokenWallet teamWallet = tokenService.getOrCreateTeamWallet(team);
            tokenService.grantPeriodTokens(teamWallet, plan, actualSeats, periodEnd, key);
            log.info("[구독] {} TEAM 활성화 — team={} 팀원 {}명, 주기 종료 {}",
                    user.getUserId(), team.getTeamId(), actualSeats, periodEnd);
        } else {
            tokenService.grantPeriodTokens(user, plan, seats, periodEnd, key);
            log.info("[구독] {} {} 활성화 — 주기 종료 {}", user.getUserId(), plan, periodEnd);
        }
        return saved;
    }

    /** 간편 클릭 해지. 현재 주기 종료 시 갱신하지 않는다(기간 내 잔여 토큰은 그대로 사용). */
    @Transactional
    public Subscription cancelAtPeriodEnd(User user) {
        Subscription subscription = subscriptionRepository.findByUser(user)
                .orElseThrow(() -> new IllegalStateException("구독 정보가 없습니다."));
        subscription.setCancelAtPeriodEnd(true);
        log.info("[구독] {} 해지 예약 — {}까지 사용 가능",
                user.getUserId(), subscription.getCurrentPeriodEnd());
        return subscriptionRepository.save(subscription);
    }

    /** 해지 예약 취소(구독 유지). */
    @Transactional
    public Subscription resume(User user) {
        Subscription subscription = subscriptionRepository.findByUser(user)
                .orElseThrow(() -> new IllegalStateException("구독 정보가 없습니다."));
        subscription.setCancelAtPeriodEnd(false);
        return subscriptionRepository.save(subscription);
    }

    // ── 배치 ────────────────────────────────────────────────────────────────

    /**
     * 체험 종료 1일 전 알림 대상. PRO 체험이면서 해지 예약을 하지 않았고,
     * 아직 알림을 보내지 않은 구독만 고른다({@code trial_notified_at IS NULL}).
     * 배치가 매시 돌아도 같은 구독에 중복 발송되지 않는다.
     */
    @Transactional(readOnly = true)
    public List<Subscription> trialsEndingSoon() {
        LocalDateTime now = LocalDateTime.now();
        return subscriptionRepository.findByStatusAndPlan(SubscriptionStatus.TRIALING, Plan.PRO)
                .stream()
                .filter(s -> !s.isCancelAtPeriodEnd())
                .filter(s -> s.getTrialNotifiedAt() == null)
                .filter(s -> s.getTrialEnd() != null)
                .filter(s -> s.getTrialEnd().isAfter(now)
                          && s.getTrialEnd().isBefore(now.plusDays(TokenPolicy.TRIAL_NOTICE_DAYS_BEFORE)))
                .toList();
    }

    /** 알림 발송 성공 시 호출 — 같은 구독에 다시 보내지 않도록 표시한다. */
    @Transactional
    public void markTrialNotified(Subscription subscription) {
        subscription.setTrialNotifiedAt(LocalDateTime.now());
        subscriptionRepository.save(subscription);
    }

    /**
     * 체험 종료 처리. 해지 예약이면 FREE로 내리고, 아니면 결제 대상으로 넘긴다.
     * 어느 쪽이든 잔여 체험 토큰은 여기서 사라진다(0으로 덮어쓰기 또는 월 지급량으로 교체).
     */
    @Transactional
    public void expireTrial(Subscription subscription) {
        User user = subscription.getUser();
        if (subscription.isCancelAtPeriodEnd()) {
            subscription.setStatus(SubscriptionStatus.CANCELED);
            subscription.setTrialEnd(null);
            subscriptionRepository.save(subscription);
            tokenService.grantPeriodTokens(user, Plan.FREE, 1, null,
                    "trial-end:" + subscription.getSubscriptionId());
            log.info("[구독] {} 체험 종료 — 해지 처리", user.getUserId());
            return;
        }
        // 결제 성공 시 activate()가 호출되어 50,000토큰이 새로 지급된다.
        subscription.setStatus(SubscriptionStatus.PAST_DUE);
        subscriptionRepository.save(subscription);
        log.info("[구독] {} 체험 종료 — 결제 대기", user.getUserId());
    }
}

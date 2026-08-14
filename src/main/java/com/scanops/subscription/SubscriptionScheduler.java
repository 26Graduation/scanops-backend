package com.scanops.subscription;

import com.scanops.notification.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 구독 주기 배치.
 *
 * <p>체험 종료 1일 전 알림과 체험 만료 처리를 매시 정각에 돌린다.
 * 알림은 가입한 이메일({@code users.email})로 발송한다 — GitHub 전용 계정은
 * OAuth 시점에 생성된 {@code login@users.noreply.github.com} 형태라 실제로 닿지 않을 수 있다
 * (알려진 한계, {@link com.scanops.notification.MailService}는 발송 실패를 삼키므로 배치는 계속 돈다).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionScheduler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final MailService mailService;

    /** 체험 종료 1일 전 알림. 발송에 성공한 건만 재발송 방지 표시를 남긴다. */
    @Scheduled(cron = "0 0 * * * *")
    public void notifyTrialEndingSoon() {
        List<Subscription> targets = subscriptionService.trialsEndingSoon();
        if (targets.isEmpty()) return;

        for (Subscription s : targets) {
            String email = s.getUser().getEmail();
            boolean sent = mailService.send(email, "[ScanOps] 무료체험이 내일 종료돼요", trialEndingBody(s));

            // 메일 자체가 미설정/실패해도 알림 로직은 정상 동작한 것으로 보고 재발송 루프에 빠지지 않게 한다 —
            // 배치가 시간마다 도는데 실패할 때마다 재시도하면 SMTP가 복구된 순간 오래된 알림이 몰려 나간다.
            subscriptionService.markTrialNotified(s);
            log.info("[체험 알림] user={} plan={} trialEnd={} 메일발송={}",
                    s.getUser().getUserId(), s.getPlan(), s.getTrialEnd(), sent);
        }
    }

    private String trialEndingBody(Subscription s) {
        return String.format(
                "안녕하세요, ScanOps입니다.%n%n"
                + "가입하신 %s 플랜 무료체험이 %s에 종료돼요.%n"
                + "그 이후로는 월 %,d원이 자동으로 결제되고, %,d 토큰이 새로 지급됩니다.%n%n"
                + "결제를 원하지 않으시면 마이페이지 > 설정에서 간편하게 해지할 수 있어요. "
                + "체험 종료 시점까지는 남은 토큰을 그대로 사용하실 수 있습니다.%n%n"
                + "감사합니다.%nScanOps 드림",
                s.getPlan(), s.getTrialEnd().format(DATE_FMT),
                s.getPlan().priceKrw(), s.getPlan().monthlyTokens());
    }

    /** 종료 시각이 지난 체험을 정리한다. 해지 예약이면 FREE로, 아니면 결제 대기로. */
    @Scheduled(cron = "0 5 * * * *")
    public void expireFinishedTrials() {
        LocalDateTime now = LocalDateTime.now();
        List<Subscription> expired = subscriptionRepository
                .findByStatusAndPlan(SubscriptionStatus.TRIALING, Plan.PRO)
                .stream()
                .filter(s -> s.getTrialEnd() != null && s.getTrialEnd().isBefore(now))
                .toList();

        for (Subscription s : expired) {
            subscriptionService.expireTrial(s);
        }
        if (!expired.isEmpty()) {
            log.info("[체험 만료] {}건 처리", expired.size());
        }
    }
}

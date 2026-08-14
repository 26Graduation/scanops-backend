package com.scanops.scan;

import com.scanops.subscription.Plan;
import com.scanops.token.BillingResolver;
import com.scanops.token.HoldUnit;
import com.scanops.token.InsufficientTokensException;
import com.scanops.token.TokenPolicy;
import com.scanops.token.TokenService;
import com.scanops.user.User;
import com.scanops.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * PR diff 스캔 과금.
 *
 * <p>PR 스캔은 웹훅/GitHub Action이 자동으로 트리거하므로 스캔 레코드도, 로그인 세션도 없다.
 * 레포 소유자의 GitHub 숫자 id로 계정을 찾아 그 지갑에서 차감한다.
 * 계정을 못 찾으면 <b>과금하지 않고 스캔은 그대로 진행</b>한다 — 자동 파이프라인을 돈 문제로
 * 끊는 것보다 로그를 남기고 넘어가는 편이 낫다.
 *
 * <p>멱등 키는 {@code pr:<repo>#<pr>@<sha>}다. GitHub 웹훅은 실패 시 재전송하는데,
 * 같은 커밋에 대한 재분석이 이중 차감되면 안 된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PrScanBilling {

    private final UserRepository userRepository;
    private final BillingResolver billingResolver;
    private final TokenService tokenService;

    /**
     * PR 스캔 예약. 과금 대상이면 멱등 키를, 아니면 empty를 돌려준다.
     * 잔액이 부족해도 스캔은 막지 않고 무과금으로 진행한다(무료 티어 보호).
     */
    public Optional<String> hold(String ownerGithubId, String repo, int prNumber,
                                 String headSha, long changedLines) {
        if (ownerGithubId == null || ownerGithubId.isBlank()) {
            log.warn("[PR 과금] 레포 소유자 정보 없음 — 무과금 진행: {}#{}", repo, prNumber);
            return Optional.empty();
        }
        Optional<User> owner = userRepository.findByGithubId(ownerGithubId);
        if (owner.isEmpty()) {
            log.warn("[PR 과금] ScanOps 계정을 찾지 못함(github id={}) — 무과금 진행: {}#{}",
                    ownerGithubId, repo, prNumber);
            return Optional.empty();
        }

        long tokens = TokenPolicy.tokensForPrLines(changedLines);
        String key = key(repo, prNumber, headSha);
        BillingResolver.Billing billing = billingResolver.resolve(owner.get());

        try {
            tokenService.hold(billing.wallet(), billing.plan(), HoldUnit.TOKEN, tokens, key, null);
            log.info("[PR 과금] {}#{} 예약 {} 토큰 ({}줄, 플랜 {}{})",
                    repo, prNumber, tokens, changedLines, billing.plan(), billing.team() ? ", 팀 공유 지갑" : "");
            return Optional.of(key);
        } catch (InsufficientTokensException e) {
            log.warn("[PR 과금] 잔액 부족 — 무과금 진행: {}#{} ({})", repo, prNumber, e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("[PR 과금] 예약 실패 — 무과금 진행: {}#{} ({})", repo, prNumber, e.getMessage());
            return Optional.empty();
        }
    }

    /** 분석 성공 → 실제 변경 줄 수로 정산. */
    public void commit(Optional<String> key, long changedLines) {
        key.ifPresent(k -> tokenService.commit(
                k, TokenPolicy.tokensForPrLines(changedLines),
                String.format("PR 스캔 %,d줄", changedLines)));
    }

    /** 분석 실패·무응답 → 전액 반환. */
    public void release(Optional<String> key, String reason) {
        key.ifPresent(k -> tokenService.release(k, reason));
    }

    static String key(String repo, int prNumber, String headSha) {
        return "pr:" + repo + "#" + prNumber + "@" + (headSha == null ? "" : headSha);
    }
}

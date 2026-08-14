package com.scanops.token;

import com.scanops.subscription.Plan;
import com.scanops.subscription.SubscriptionService;
import com.scanops.team.Team;
import com.scanops.team.TeamMember;
import com.scanops.team.TeamMemberRepository;
import com.scanops.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 스캔 한 건이 "누구의 지갑에서, 어느 플랜 한도로" 과금되는지 결정한다.
 *
 * <p>사용자가 TEAM 플랜 팀의 멤버면 그 팀의 공유 지갑을 쓰고, 아니면 본인 개인 지갑을 쓴다.
 * 판단 기준은 팀장(owner)의 개인 구독이 TEAM 플랜으로 활성 상태인지 하나뿐이다 — 팀 자체에는
 * 별도 구독 레코드가 없다({@link Team} 문서 참고). 이 클래스가 스캔 파이프라인·PR 과금·잔액
 * 조회가 공유하는 유일한 진입점이므로, 지갑 판정 로직은 여기 한 곳에만 있어야 한다.
 */
@Component
@RequiredArgsConstructor
public class BillingResolver {

    private final TeamMemberRepository teamMemberRepository;
    private final SubscriptionService subscriptionService;
    private final TokenService tokenService;

    public record Billing(Plan plan, TokenWallet wallet, boolean team) {}

    @Transactional
    public Billing resolve(User user) {
        Optional<TeamMember> membership = teamMemberRepository.findByUser_UserId(user.getUserId());
        if (membership.isPresent()) {
            Team team = membership.get().getTeam();
            Plan teamPlan = subscriptionService.currentPlan(team.getOwnerUser().getUserId());
            if (teamPlan == Plan.TEAM) {
                return new Billing(Plan.TEAM, tokenService.getOrCreateTeamWallet(team), true);
            }
            // 팀은 있지만 결제가 끊긴 상태(해지/연체) — 개인 지갑으로 폴백, 한도는 FREE로 떨어진다.
        }
        Plan plan = subscriptionService.currentPlan(user.getUserId());
        return new Billing(plan, tokenService.getOrCreateWallet(user), false);
    }
}

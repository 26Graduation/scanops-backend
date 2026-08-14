package com.scanops.token;

import com.scanops.subscription.Plan;
import com.scanops.subscription.SubscriptionService;
import com.scanops.team.Team;
import com.scanops.team.TeamMember;
import com.scanops.team.TeamMemberRepository;
import com.scanops.team.TeamRole;
import com.scanops.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "누구의 지갑에서 차감되는가"를 결정하는 핵심 분기 검증.
 * TEAM 플랜이 실제로 활성 상태일 때만 팀 공유 지갑을 쓰고, 그 외에는 항상 개인 지갑으로 폴백해야 한다.
 */
class BillingResolverTest {

    private TeamMemberRepository teamMemberRepository;
    private SubscriptionService subscriptionService;
    private TokenService tokenService;
    private BillingResolver resolver;

    private User user;
    private TokenWallet personalWallet;
    private TokenWallet teamWallet;

    @BeforeEach
    void setUp() {
        teamMemberRepository = mock(TeamMemberRepository.class);
        subscriptionService = mock(SubscriptionService.class);
        tokenService = mock(TokenService.class);
        resolver = new BillingResolver(teamMemberRepository, subscriptionService, tokenService);

        user = User.builder().userId(UUID.randomUUID()).email("member@example.com").build();
        personalWallet = TokenWallet.builder().walletId(UUID.randomUUID()).user(user).build();
        teamWallet = TokenWallet.builder().walletId(UUID.randomUUID()).build();

        when(tokenService.getOrCreateWallet(any())).thenReturn(personalWallet);
        when(tokenService.getOrCreateTeamWallet(any())).thenReturn(teamWallet);
    }

    @Test
    @DisplayName("팀에 속하지 않은 사용자는 개인 지갑을 쓴다")
    void nonMemberUsesPersonalWallet() {
        when(teamMemberRepository.findByUser_UserId(user.getUserId())).thenReturn(Optional.empty());
        when(subscriptionService.currentPlan(user.getUserId())).thenReturn(Plan.PRO);

        BillingResolver.Billing billing = resolver.resolve(user);

        assertFalse(billing.team());
        assertEquals(Plan.PRO, billing.plan());
        assertEquals(personalWallet, billing.wallet());
    }

    @Test
    @DisplayName("소속 팀의 TEAM 결제가 활성 상태면 공유 지갑을 쓴다")
    void teamMemberWithActiveTeamPlanUsesSharedWallet() {
        User owner = User.builder().userId(UUID.randomUUID()).email("owner@example.com").build();
        Team team = Team.builder().teamId(UUID.randomUUID()).name("우리팀").ownerUser(owner).build();
        TeamMember membership = TeamMember.builder().team(team).user(user).role(TeamRole.MEMBER).build();

        when(teamMemberRepository.findByUser_UserId(user.getUserId())).thenReturn(Optional.of(membership));
        when(subscriptionService.currentPlan(owner.getUserId())).thenReturn(Plan.TEAM);

        BillingResolver.Billing billing = resolver.resolve(user);

        assertTrue(billing.team());
        assertEquals(Plan.TEAM, billing.plan());
        assertEquals(teamWallet, billing.wallet());
    }

    @Test
    @DisplayName("팀은 있지만 결제가 끊긴 상태(해지/연체)면 개인 지갑으로 폴백한다")
    void lapsedTeamBillingFallsBackToPersonalWallet() {
        User owner = User.builder().userId(UUID.randomUUID()).email("owner@example.com").build();
        Team team = Team.builder().teamId(UUID.randomUUID()).name("우리팀").ownerUser(owner).build();
        TeamMember membership = TeamMember.builder().team(team).user(user).role(TeamRole.MEMBER).build();

        when(teamMemberRepository.findByUser_UserId(user.getUserId())).thenReturn(Optional.of(membership));
        when(subscriptionService.currentPlan(owner.getUserId())).thenReturn(Plan.FREE); // 해지됨
        when(subscriptionService.currentPlan(user.getUserId())).thenReturn(Plan.FREE);  // 멤버 개인은 FREE

        BillingResolver.Billing billing = resolver.resolve(user);

        assertFalse(billing.team());
        assertEquals(Plan.FREE, billing.plan());
        assertEquals(personalWallet, billing.wallet());
    }
}

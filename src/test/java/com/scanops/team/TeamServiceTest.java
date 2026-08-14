package com.scanops.team;

import com.scanops.user.User;
import com.scanops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TeamServiceTest {

    private TeamRepository teamRepository;
    private TeamMemberRepository teamMemberRepository;
    private UserRepository userRepository;
    private TeamService teamService;

    private User owner;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        userRepository = mock(UserRepository.class);
        teamService = new TeamService(teamRepository, teamMemberRepository, userRepository);

        owner = User.builder().userId(UUID.randomUUID()).email("owner@example.com").build();
        when(teamRepository.save(any(Team.class))).thenAnswer(i -> {
            Team t = i.getArgument(0);
            if (t.getTeamId() == null) t.setTeamId(UUID.randomUUID());
            return t;
        });
        when(teamMemberRepository.save(any(TeamMember.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("팀 생성 시 생성자가 OWNER로 등록된다")
    void createTeamRegistersOwner() {
        when(teamMemberRepository.existsByUser_UserId(owner.getUserId())).thenReturn(false);

        Team team = teamService.createTeam(owner, "우리팀");

        assertEquals("우리팀", team.getName());
        assertEquals(owner, team.getOwnerUser());
        verify(teamMemberRepository).save(argThat(m -> m.getRole() == TeamRole.OWNER && m.getUser() == owner));
    }

    @Test
    @DisplayName("이미 다른 팀에 속해 있으면 새 팀을 만들 수 없다")
    void cannotCreateSecondTeam() {
        when(teamMemberRepository.existsByUser_UserId(owner.getUserId())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> teamService.createTeam(owner, "두번째팀"));
    }

    @Test
    @DisplayName("가입된 계정이 아니면 초대할 수 없다")
    void cannotInviteUnknownEmail() {
        Team team = Team.builder().teamId(UUID.randomUUID()).name("우리팀").ownerUser(owner).build();
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> teamService.addMemberByEmail(team, "nobody@example.com", TeamRole.MEMBER));
    }

    @Test
    @DisplayName("이미 다른 팀 소속인 계정은 초대할 수 없다")
    void cannotInviteMemberOfAnotherTeam() {
        Team team = Team.builder().teamId(UUID.randomUUID()).name("우리팀").ownerUser(owner).build();
        User already = User.builder().userId(UUID.randomUUID()).email("busy@example.com").build();
        when(userRepository.findByEmail("busy@example.com")).thenReturn(Optional.of(already));
        when(teamMemberRepository.existsByUser_UserId(already.getUserId())).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> teamService.addMemberByEmail(team, "busy@example.com", TeamRole.MEMBER));
    }

    @Test
    @DisplayName("OWNER 역할로는 초대할 수 없다 — OWNER는 팀 생성자에게만 부여된다")
    void cannotInviteAsOwner() {
        Team team = Team.builder().teamId(UUID.randomUUID()).name("우리팀").ownerUser(owner).build();
        User invitee = User.builder().userId(UUID.randomUUID()).email("new@example.com").build();
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.of(invitee));
        when(teamMemberRepository.existsByUser_UserId(invitee.getUserId())).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> teamService.addMemberByEmail(team, "new@example.com", TeamRole.OWNER));
    }

    @Test
    @DisplayName("OWNER는 제거할 수 없다")
    void cannotRemoveOwner() {
        Team team = Team.builder().teamId(UUID.randomUUID()).name("우리팀").ownerUser(owner).build();
        TeamMember ownerMembership = TeamMember.builder().team(team).user(owner).role(TeamRole.OWNER).build();
        when(teamMemberRepository.findByTeam_TeamIdAndUser_UserId(team.getTeamId(), owner.getUserId()))
                .thenReturn(Optional.of(ownerMembership));

        assertThrows(IllegalArgumentException.class, () -> teamService.removeMember(team, owner.getUserId()));
    }

    @Test
    @DisplayName("일반 멤버는 정상적으로 제거된다")
    void removesRegularMember() {
        Team team = Team.builder().teamId(UUID.randomUUID()).name("우리팀").ownerUser(owner).build();
        User member = User.builder().userId(UUID.randomUUID()).email("member@example.com").build();
        TeamMember membership = TeamMember.builder().team(team).user(member).role(TeamRole.MEMBER).build();
        when(teamMemberRepository.findByTeam_TeamIdAndUser_UserId(team.getTeamId(), member.getUserId()))
                .thenReturn(Optional.of(membership));

        teamService.removeMember(team, member.getUserId());

        verify(teamMemberRepository).delete(membership);
    }
}

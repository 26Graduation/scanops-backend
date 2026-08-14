package com.scanops.team;

import com.scanops.user.User;
import com.scanops.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 팀 생성/구성원 관리.
 *
 * <p>초대 메일이나 대기(pending) 상태는 없다 — "초대"는 이미 가입한 ScanOps 계정을
 * 이메일로 찾아 즉시 멤버로 추가하는 방식이다(MVP 범위). 계정이 없는 이메일을 초대하려면
 * 먼저 가입을 안내해야 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;

    /** 팀 생성 + 생성자를 OWNER로 등록. 이미 다른 팀 소속이면 만들 수 없다. */
    @Transactional
    public Team createTeam(User owner, String name) {
        if (teamMemberRepository.existsByUser_UserId(owner.getUserId())) {
            throw new IllegalStateException("이미 다른 팀에 속해 있어 새 팀을 만들 수 없습니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("팀 이름을 입력해 주세요.");
        }

        Team team = teamRepository.save(Team.builder().name(name.trim()).ownerUser(owner).build());
        teamMemberRepository.save(TeamMember.builder().team(team).user(owner).role(TeamRole.OWNER).build());
        log.info("[팀] {} 생성 — owner={}", team.getTeamId(), owner.getUserId());
        return team;
    }

    @Transactional(readOnly = true)
    public Team requireTeamOf(UUID userId) {
        return teamMemberRepository.findByUser_UserId(userId)
                .map(TeamMember::getTeam)
                .orElseThrow(() -> new IllegalStateException("소속된 팀이 없습니다."));
    }

    /**
     * 이메일로 기존 계정을 찾아 멤버로 추가한다.
     * @throws IllegalArgumentException 계정이 없거나 이미 다른 팀 소속인 경우
     */
    @Transactional
    public TeamMember addMemberByEmail(Team team, String email, TeamRole role) {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new IllegalArgumentException(
                        "가입된 계정을 찾을 수 없습니다: " + email + " (먼저 ScanOps에 가입해야 초대할 수 있어요)"));

        if (teamMemberRepository.existsByUser_UserId(user.getUserId())) {
            throw new IllegalArgumentException(email + "님은 이미 다른 팀에 속해 있습니다.");
        }
        if (role == TeamRole.OWNER) {
            throw new IllegalArgumentException("OWNER는 팀 생성자에게만 부여됩니다.");
        }

        TeamMember member = teamMemberRepository.save(
                TeamMember.builder().team(team).user(user).role(role).build());
        log.info("[팀] {} 멤버 추가 — user={} role={}", team.getTeamId(), user.getUserId(), role);
        return member;
    }

    /** 멤버 제거. OWNER는 제거할 수 없다(팀 삭제 절차가 별도로 필요). */
    @Transactional
    public void removeMember(Team team, UUID targetUserId) {
        TeamMember member = teamMemberRepository.findByTeam_TeamIdAndUser_UserId(team.getTeamId(), targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("해당 팀의 멤버가 아닙니다."));
        if (member.getRole() == TeamRole.OWNER) {
            throw new IllegalArgumentException("팀장은 제거할 수 없습니다.");
        }
        teamMemberRepository.delete(member);
        log.info("[팀] {} 멤버 제거 — user={}", team.getTeamId(), targetUserId);
    }

    @Transactional(readOnly = true)
    public List<TeamMember> members(UUID teamId) {
        return teamMemberRepository.findByTeam_TeamIdOrderByJoinedAtAsc(teamId);
    }

    @Transactional(readOnly = true)
    public long seatCount(UUID teamId) {
        return teamMemberRepository.countByTeam_TeamId(teamId);
    }

    /** 요청자가 멤버 관리 권한(OWNER/ADMIN)이 있는지. */
    @Transactional(readOnly = true)
    public boolean canManageMembers(UUID teamId, UUID requesterUserId) {
        return teamMemberRepository.findByTeam_TeamIdAndUser_UserId(teamId, requesterUserId)
                .map(m -> m.getRole() == TeamRole.OWNER || m.getRole() == TeamRole.ADMIN)
                .orElse(false);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}

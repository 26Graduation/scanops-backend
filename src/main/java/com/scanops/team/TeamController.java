package com.scanops.team;

import com.scanops.auth.JwtService;
import com.scanops.token.BillingResolver;
import com.scanops.token.TokenWallet;
import com.scanops.user.User;
import com.scanops.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 팀(조직 계정) 관리 API. 조직 계정·구성원 관리는 TEAM 플랜의 핵심 기능이다.
 *
 * <p>초대는 이메일로 기존 ScanOps 계정을 즉시 추가하는 방식이다(대기 상태 없음, MVP 범위) —
 * 자세한 제약은 {@link TeamService} 문서 참고.
 */
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final TeamMemberRepository teamMemberRepository;
    private final BillingResolver billingResolver;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public record CreateTeamRequest(String name) {}
    public record AddMemberRequest(String email, TeamRole role) {}

    /** 팀 생성. 이미 팀에 속해 있으면 실패한다(계정당 팀 1개, MVP). */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody CreateTeamRequest req) {
        User user = requireUser(authorization);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        Team team = teamService.createTeam(user, req.name());
        return ResponseEntity.ok(Map.of("teamId", team.getTeamId(), "name", team.getName()));
    }

    /** 내가 속한 팀 정보 + 멤버 목록 + 공유 지갑 잔액(TEAM 플랜으로 활성화된 경우). */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        User user = requireUser(authorization);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        return teamMemberRepository.findByUser_UserId(user.getUserId())
                .<ResponseEntity<?>>map(membership -> {
                    Team team = membership.getTeam();
                    List<Map<String, Object>> members = teamService.members(team.getTeamId()).stream()
                            .map(this::memberInfo)
                            .toList();

                    BillingResolver.Billing billing = billingResolver.resolve(user);

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("teamId", team.getTeamId());
                    body.put("name", team.getName());
                    body.put("myRole", membership.getRole().name());
                    body.put("isOwner", team.getOwnerUser().getUserId().equals(user.getUserId()));
                    body.put("members", members);
                    body.put("planActive", billing.team());   // TEAM 결제가 실제로 활성화됐는지
                    if (billing.team()) {
                        TokenWallet wallet = billing.wallet();
                        body.put("sharedWalletAvailable", wallet.available());
                        body.put("sharedWalletHeld", wallet.getHeldBalance());
                        body.put("sharedDastAvailable", wallet.dastAvailable());
                        body.put("sharedDastHeld", wallet.getDastHeld());
                    }
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.ok(Map.of("teamId", (Object) null)));
    }

    private Map<String, Object> memberInfo(TeamMember m) {
        Map<String, Object> mi = new LinkedHashMap<>();
        mi.put("userId", m.getUser().getUserId());
        mi.put("name", m.getUser().getName());
        mi.put("email", m.getUser().getEmail());
        mi.put("role", m.getRole().name());
        mi.put("joinedAt", m.getJoinedAt());
        return mi;
    }

    /** 멤버 추가(초대). OWNER/ADMIN만 가능. */
    @PostMapping("/members")
    public ResponseEntity<?> addMember(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AddMemberRequest req) {
        User user = requireUser(authorization);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        Team team = teamService.requireTeamOf(user.getUserId());
        if (!teamService.canManageMembers(team.getTeamId(), user.getUserId())) {
            return ResponseEntity.status(403).body(Map.of("error", "멤버 초대 권한이 없습니다."));
        }
        TeamRole role = req.role() == null ? TeamRole.MEMBER : req.role();
        TeamMember member = teamService.addMemberByEmail(team, req.email(), role);
        return ResponseEntity.ok(memberInfo(member));
    }

    /** 멤버 제거. OWNER/ADMIN만 가능, OWNER 본인은 제거 불가. */
    @DeleteMapping("/members/{userId}")
    public ResponseEntity<?> removeMember(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID userId) {
        User user = requireUser(authorization);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        Team team = teamService.requireTeamOf(user.getUserId());
        if (!teamService.canManageMembers(team.getTeamId(), user.getUserId())) {
            return ResponseEntity.status(403).body(Map.of("error", "멤버 제거 권한이 없습니다."));
        }
        teamService.removeMember(team, userId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    private User requireUser(String authorization) {
        UUID userId = userId(authorization);
        return userId == null ? null : userRepository.findById(userId).orElse(null);
    }

    private UUID userId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        try {
            Claims claims = jwtService.parse(authorization.substring(7));
            return UUID.fromString(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }
}

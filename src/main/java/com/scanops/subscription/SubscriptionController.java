package com.scanops.subscription;

import com.scanops.auth.JwtService;
import com.scanops.team.TeamMemberRepository;
import com.scanops.team.TeamRepository;
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
 * 구독 생명주기 API.
 *
 * <p><b>PG(결제 게이트웨이) 연동은 이 범위에 없다.</b> {@code /activate}는 프론트가 결제
 * 완료를 확인한 뒤 호출하는 "확정" 엔드포인트로, 카드 검증이나 실제 결제 승인은 하지 않는다.
 * 실서비스 전환 시 PG 웹훅(결제 성공 콜백)에서 {@link SubscriptionService#activate}를
 * 호출하도록 바꾸고, 이 엔드포인트는 그 웹훅 전용으로 좁히거나 제거해야 한다.
 */
@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository subscriptionRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public record ActivateRequest(Plan plan, Integer seats, String paymentId) {}

    /** 요금제 카탈로그 — 가격/토큰/한도. 로그인 불필요(가격 페이지용). */
    @GetMapping("/plans")
    public ResponseEntity<List<Map<String, Object>>> plans() {
        List<Map<String, Object>> body = List.of(
                planInfo(Plan.FREE), planInfo(Plan.PRO), planInfo(Plan.MAX), planInfo(Plan.TEAM));
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> planInfo(Plan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", plan.name());
        m.put("priceKrw", plan.priceKrw());
        // 토큰(SAST·액션)
        m.put("monthlyTokens", plan.monthlyTokens());
        m.put("trialTokens", plan.trialTokens());
        // DAST(웹 점검) — 토큰과 별개인 횟수 한도
        m.put("dastMonthlyLimit", plan.dastMonthlyLimit());
        m.put("trialDastCount", plan.trialDastCount());
        m.put("trialEligible", plan.trialEligible());
        m.put("trialDays", plan.trialEligible() ? com.scanops.token.TokenPolicy.TRIAL_DAYS : 0);
        m.put("maxConcurrentDastScans", plan.maxConcurrentDastScans());
        m.put("maxConcurrentSastScans", plan.maxConcurrentSastScans());
        m.put("maxFilesPerScan", plan.maxFilesPerScan());
        if (plan == Plan.TEAM) {
            m.put("baseSeats", 3);
            m.put("extraSeatPriceKrw", plan.extraSeatPriceKrw());
            m.put("extraSeatTokens", plan.extraSeatTokens());
            m.put("dastExtraSeat", plan.dastExtraSeat());
        }
        return m;
    }

    /** 현재 구독 상태. 구독이 없으면 FREE로 응답한다. */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID userId = userId(authorization);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        return subscriptionRepository.findByUser_UserId(userId)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(Map.of(
                        "plan", s.getPlan().name(),
                        "status", s.getStatus().name(),
                        "trialEnd", String.valueOf(s.getTrialEnd()),
                        "currentPeriodEnd", String.valueOf(s.getCurrentPeriodEnd()),
                        "cancelAtPeriodEnd", s.isCancelAtPeriodEnd())))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "plan", Plan.FREE.name(), "status", "NONE",
                        "cancelAtPeriodEnd", false)));
    }

    /**
     * 7일 무료체험 시작. PRO 전용, 계정당 1회.
     * 카드 등록 없이 시작되므로 실서비스에서는 PG의 "체험 후 자동결제" 카드 등록 절차가 이 앞에 와야 한다.
     */
    @PostMapping("/trial")
    public ResponseEntity<?> startTrial(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        User user = requireUser(authorization);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        Subscription s = subscriptionService.startTrial(user, Plan.PRO);
        return ResponseEntity.ok(Map.of(
                "plan", s.getPlan().name(), "status", s.getStatus().name(),
                "trialEnd", String.valueOf(s.getTrialEnd())));
    }

    /**
     * 결제 확정 → 플랜 활성화 + 토큰 지급.
     *
     * <p>⚠️ 결제 자체는 검증하지 않는다(PG 미연동). {@code paymentId}는 지금은 지급 멱등 키로만
     * 쓰이고, 실서비스에서는 PG가 서명한 웹훅 payload로 대체해야 한다.
     *
     * <p>TEAM 플랜은 요청의 {@code seats}를 신뢰하지 않는다 — 실제 팀원 수로
     * {@link SubscriptionService#activate}가 다시 계산하므로, 여기서는 응답 표시용 가격도
     * 그 실제 인원수로 다시 조회해서 보여준다.
     */
    @PostMapping("/activate")
    public ResponseEntity<?> activate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ActivateRequest req) {
        User user = requireUser(authorization);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));
        if (req.plan() == null) return ResponseEntity.badRequest().body(Map.of("error", "plan은 필수입니다"));

        Subscription s = subscriptionService.activate(user, req.plan(), 3, req.paymentId());

        int billedSeats = req.plan() == Plan.TEAM
                ? teamRepository.findByOwnerUser_UserId(user.getUserId())
                        .map(t -> (int) teamMemberRepository.countByTeam_TeamId(t.getTeamId()))
                        .orElse(3)
                : 1;

        return ResponseEntity.ok(Map.of(
                "plan", s.getPlan().name(), "status", s.getStatus().name(),
                "currentPeriodEnd", String.valueOf(s.getCurrentPeriodEnd()),
                "seats", billedSeats,
                "priceKrw", req.plan().priceForSeats(billedSeats)));
    }

    /** 간편 클릭 해지 — 현재 주기 종료까지는 그대로 사용할 수 있다. */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        User user = requireUser(authorization);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        Subscription s = subscriptionService.cancelAtPeriodEnd(user);
        return ResponseEntity.ok(Map.of(
                "cancelAtPeriodEnd", s.isCancelAtPeriodEnd(),
                "currentPeriodEnd", String.valueOf(s.getCurrentPeriodEnd())));
    }

    /** 해지 예약 취소. */
    @PostMapping("/resume")
    public ResponseEntity<?> resume(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        User user = requireUser(authorization);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        Subscription s = subscriptionService.resume(user);
        return ResponseEntity.ok(Map.of("cancelAtPeriodEnd", s.isCancelAtPeriodEnd()));
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

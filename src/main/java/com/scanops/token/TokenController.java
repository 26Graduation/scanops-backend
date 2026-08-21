package com.scanops.token;

import com.scanops.auth.JwtService;
import com.scanops.subscription.Plan;
import com.scanops.user.User;
import com.scanops.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 토큰 잔액·사용 내역 조회. 마이페이지의 "남은 토큰" 표시와 충전 유도에 쓴다.
 */
@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final TokenService tokenService;
    private final BillingResolver billingResolver;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    /**
     * 잔액 + 플랜 한도 + 환산 정보.
     * TEAM 플랜 멤버라면 개인 지갑이 아니라 팀 공유 지갑 잔액이 내려간다({@code team: true}).
     */
    @GetMapping("/wallet")
    public ResponseEntity<?> wallet(
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        UUID userId = userId(authorization);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "계정을 찾을 수 없습니다"));

        BillingResolver.Billing billing = billingResolver.resolve(user);
        TokenWallet wallet = billing.wallet();
        Plan plan = billing.plan();
        long available = wallet.available();
        long dastAvailable = wallet.dastAvailable();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", plan.name());
        body.put("team", billing.team());
        // 토큰(SAST·액션 전용)
        body.put("subscriptionBalance", wallet.getSubscriptionBalance());
        body.put("purchasedBalance", wallet.getPurchasedBalance());
        body.put("heldBalance", wallet.getHeldBalance());
        body.put("available", available);
        body.put("monthlyGrant", plan.monthlyTokens());
        // 먼저 나누면 300토큰(=1,000줄) 단위로 뭉텅이로 버려져 실제 차감한 줄 수와 표시가
        // 어긋난다(예: 411토큰 차감돼도 표시는 1,000줄 단위로만 변함). 곱셈을 먼저 해서
        // 토큰 1개 단위의 정밀도로 환산한다.
        body.put("sourceLinesLeft", available * 1_000 / TokenPolicy.SAST_TOKENS_PER_1K_LINES);
        // 월 한도도 같은 환산식으로 내려줘야 프론트에서 "사용/한도" 바를 그릴 수 있다.
        body.put("sourceLinesMonthlyLimit", (long) plan.monthlyTokens() * 1_000 / TokenPolicy.SAST_TOKENS_PER_1K_LINES);
        // DAST(웹 점검) — 토큰과 별개인 횟수 카운터
        body.put("dastSubscriptionRemaining", wallet.getDastSubscriptionRemaining());
        body.put("dastPurchasedRemaining", wallet.getDastPurchasedRemaining());
        body.put("dastHeld", wallet.getDastHeld());
        body.put("dastAvailable", dastAvailable);
        body.put("dastMonthlyLimit", plan.dastMonthlyLimit());
        body.put("websiteScansLeft", dastAvailable);
        body.put("periodEnd", wallet.getPeriodEnd());
        body.put("maxConcurrentDastScans", plan.maxConcurrentDastScans());
        body.put("maxConcurrentSastScans", plan.maxConcurrentSastScans());
        body.put("topUpPriceKrw", TokenPolicy.PURCHASE_PRICE_KRW);
        body.put("topUpTokens", TokenPolicy.PURCHASE_TOKENS);
        body.put("dastTopUpPriceKrw", TokenPolicy.DAST_PURCHASE_PRICE_KRW);
        body.put("dastTopUpCount", TokenPolicy.DAST_PURCHASE_COUNT);
        return ResponseEntity.ok(body);
    }

    public record PurchaseRequest(Integer units, String paymentId) {}

    /**
     * 토큰 충전 확정. 5,000원 = 3,000토큰(1구좌). <b>항상 본인 개인 지갑에 충전된다</b> —
     * 팀 공유 풀 충전은 아직 지원하지 않는다(구독 갱신 지급만 공유 풀로 들어간다).
     *
     * <p>⚠️ 결제 자체는 검증하지 않는다(PG 미연동, {@link com.scanops.subscription.SubscriptionController}와
     * 동일한 한계). {@code paymentId}는 지급 멱등 키로만 쓰인다.
     */
    @PostMapping("/purchase")
    public ResponseEntity<?> purchase(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody PurchaseRequest req) {

        UUID userId = userId(authorization);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        int units = req.units() == null ? 0 : req.units();
        if (units <= 0) return ResponseEntity.badRequest().body(Map.of("error", "units는 1 이상이어야 합니다"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "계정을 찾을 수 없습니다"));

        TokenWallet wallet = tokenService.purchase(user, units, req.paymentId());
        return ResponseEntity.ok(Map.of(
                "purchasedTokens", TokenPolicy.PURCHASE_TOKENS * units,
                "priceKrw", TokenPolicy.PURCHASE_PRICE_KRW * units,
                "available", wallet.available()));
    }

    /**
     * DAST(웹 점검) 횟수 충전. 5,000원 = 3회.
     *
     * <p>⚠️ 결제 자체는 검증하지 않는다(PG 미연동). {@code paymentId}는 지급 멱등 키로만 쓰인다.
     */
    @PostMapping("/purchase-dast")
    public ResponseEntity<?> purchaseDast(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody PurchaseRequest req) {

        UUID userId = userId(authorization);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        int units = req.units() == null ? 0 : req.units();
        if (units <= 0) return ResponseEntity.badRequest().body(Map.of("error", "units는 1 이상이어야 합니다"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "계정을 찾을 수 없습니다"));

        TokenWallet wallet = tokenService.purchaseDast(user, units, req.paymentId());
        return ResponseEntity.ok(Map.of(
                "purchasedCount", TokenPolicy.DAST_PURCHASE_COUNT * units,
                "priceKrw", TokenPolicy.DAST_PURCHASE_PRICE_KRW * units,
                "dastAvailable", wallet.dastAvailable()));
    }

    /** 사용 내역(원장). 최신순. TEAM 멤버는 팀 공유 지갑의 내역(다른 멤버 사용분 포함)을 본다. */
    @GetMapping("/ledger")
    public ResponseEntity<?> ledger(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID userId = userId(authorization);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "계정을 찾을 수 없습니다"));

        TokenWallet wallet = billingResolver.resolve(user).wallet();
        return ResponseEntity.ok(tokenService.ledger(wallet.getWalletId(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))));
    }

    /** 스캔 종류별 단가표 — 요금 안내 화면용(로그인 불필요). DAST는 횟수, SAST·PR은 토큰 단위. */
    @GetMapping("/pricing")
    public ResponseEntity<List<Map<String, Object>>> pricing() {
        return ResponseEntity.ok(List.of(
                Map.of("type", "WEBSITE", "label", "웹사이트 점검", "billingUnit", "COUNT",
                       "unit", "1회", "count", TokenPolicy.DAST_PURCHASE_COUNT,
                       "krw", TokenPolicy.DAST_PURCHASE_PRICE_KRW / TokenPolicy.DAST_PURCHASE_COUNT),
                Map.of("type", "SAST", "label", "소스코드 점검", "billingUnit", "TOKEN",
                       "unit", "1,000줄", "tokens", TokenPolicy.SAST_TOKENS_PER_1K_LINES,
                       "krw", TokenPolicy.toKrw(TokenPolicy.SAST_TOKENS_PER_1K_LINES)),
                Map.of("type", "PR", "label", "PR 변경분 점검", "billingUnit", "TOKEN",
                       "unit", "1,000줄", "tokens", TokenPolicy.SAST_TOKENS_PER_1K_LINES,
                       "krw", TokenPolicy.toKrw(TokenPolicy.SAST_TOKENS_PER_1K_LINES)),
                Map.of("type", "RESCAN", "label", "24시간 내 동일 대상 재점검", "billingUnit", "COUNT",
                       "unit", "1회", "count", 0, "krw", 0)));
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

package com.scanops.scan;

import com.scanops.subscription.Plan;
import com.scanops.subscription.SubscriptionService;
import com.scanops.token.BillingResolver;
import com.scanops.token.HoldUnit;
import com.scanops.token.TokenPolicy;
import com.scanops.token.TokenService;
import com.scanops.user.User;
import com.scanops.user.UserService;
import com.scanops.verify.DomainVerifyService;
import com.scanops.vulnerability.Vulnerability;
import com.scanops.vulnerability.VulnerabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanService {

    private final ScanRepository scanRepository;
    private final UserService userService;
    private final DomainVerifyService domainVerifyService;
    private final ScanPipelineRunner pipelineRunner;
    private final GithubPipelineRunner githubPipelineRunner;
    private final VulnerabilityService vulnerabilityService;
    private final GithubScanService githubScanService;
    private final SubscriptionService subscriptionService;
    private final TokenService tokenService;
    private final BillingResolver billingResolver;

    /** DAST(웹) 스캔에 도메인 소유권 인증을 강제할지. 데모 등에서 끄려면 false. */
    @Value("${scanops.dast.require-verification:true}")
    private boolean requireDastVerification;

    /** 이 도메인은 ScanOps 자체 서비스이므로 소유권 인증 없이 DAST 스캔을 허용한다. */
    @Value("${scanops.dast.self-scan-domain:scanops-frontend.vercel.app}")
    private String selfScanDomain;

    /**
     * @param ownerId 로그인 사용자 식별자(JWT subject). WEBSITE 인증 스코프에 사용.
     *                미로그인(null)이고 인증이 강제되면 WEBSITE 스캔은 거부된다.
     */
    public Scan createScan(ScanRequest request, String ownerId) {
        // scanMode 자동 판별: github.com URL이면 GITHUB_REPO
        ScanMode mode = request.getScanMode();
        if (mode == null) {
            mode = request.getTargetUrl().contains("github.com")
                    ? ScanMode.GITHUB_REPO
                    : ScanMode.WEBSITE;
        }

        if (mode == ScanMode.GITHUB_REPO && !request.getTargetUrl().contains("github.com")) {
            throw new IllegalArgumentException(
                "GitHub 레포 분석은 github.com URL만 지원합니다. (예: https://github.com/user/repo)"
            );
        }

        if (mode == ScanMode.GITHUB_REPO) {
            String path = request.getTargetUrl()
                    .replaceFirst("^https?://github\\.com/?", "")
                    .replaceAll("\\.git$", "")
                    .replaceAll("/$", "");
            String[] parts = path.split("/");
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException(
                    "GitHub 레포 URL 형식이 올바르지 않습니다. (예: https://github.com/user/repo)"
                );
            }
        }

        // WEBSITE(DAST): 소유권을 사용자별로 확인하고, 스캔 직전 .well-known 재검증(TOCTOU 방지).
        // 단, ScanOps 자체 서비스 도메인은 로그인한 사용자에 한해 소유권 인증만 생략한다
        // (로그인 자체는 다른 도메인과 동일하게 여전히 필요하다).
        boolean verified = false;
        if (mode == ScanMode.WEBSITE) {
            if (ownerId == null) {
                if (requireDastVerification) {
                    throw new IllegalArgumentException("도메인 소유권 인증을 위해 로그인이 필요합니다.");
                }
            } else {
                String domain = domainVerifyService.extractDomain(request.getTargetUrl());
                if (domain.equalsIgnoreCase(selfScanDomain)) {
                    verified = true;
                } else {
                    verified = domainVerifyService.verifyForScan(ownerId, request.getTargetUrl());
                    if (requireDastVerification && !verified) {
                        throw new IllegalArgumentException(
                            "도메인 소유권 인증이 필요합니다. " + DomainVerifyService.VERIFY_PATH
                            + " 파일을 배포하고 인증을 완료해 주세요.");
                    }
                }
            }
        }

        User owner = userService.findOrCreateByEmail(request.getOwnerEmail());
        ScanCategory category = mode == ScanMode.GITHUB_REPO ? ScanCategory.SAST : ScanCategory.DAST;

        Scan scan = Scan.builder()
                .user(owner)
                .scanCategory(category)
                .target(request.getTargetUrl())
                .status(ScanStatus.PENDING)
                .verified(verified)
                .scanMode(mode)
                .build();

        Scan saved = scanRepository.save(scan);

        // 토큰 예약(HOLD). 잔액 부족·동시 실행 초과면 스캔 레코드를 지우고 예외를 그대로 올린다
        // (PENDING 상태로 남아 사용자 기록을 어지럽히지 않도록).
        try {
            reserveTokens(owner, saved, mode);
        } catch (RuntimeException e) {
            scanRepository.delete(saved);
            throw e;
        }

        if (mode == ScanMode.GITHUB_REPO) {
            githubPipelineRunner.run(saved);
        } else {
            pipelineRunner.run(saved);
        }
        return saved;
    }

    /**
     * 스캔 예상 비용을 예약한다.
     *
     * <ul>
     *   <li>WEBSITE(DAST): <b>횟수</b> 차감, 1스캔=1회. 24시간 내 같은 URL을 이미 성공
     *       스캔했다면 무과금(예약 자체를 안 함).</li>
     *   <li>GITHUB_REPO(SAST): <b>토큰</b> 차감. 레포 blob 크기로 줄 수를 추정해 예약하고,
     *       완료 시 실제 분석된 줄 수로 정산. 추정에 실패하면 최소 과금(300토큰)만 잡는다.</li>
     * </ul>
     */
    private void reserveTokens(User owner, Scan scan, ScanMode mode) {
        BillingResolver.Billing billing = billingResolver.resolve(owner);
        Plan plan = billing.plan();
        String key = TokenService.scanKey(scan.getScanId());

        if (mode == ScanMode.GITHUB_REPO) {
            long bytes = githubScanService.estimateAnalyzableBytes(scan.getTarget(), plan.maxFilesPerScan());
            long estimate = bytes > 0
                    ? TokenPolicy.estimateTokensForBytes(bytes)
                    : TokenPolicy.SAST_MIN_TOKENS;
            tokenService.hold(billing.wallet(), plan, HoldUnit.TOKEN, estimate, key, scan.getScanId());
            log.info("[토큰] 스캔 {} 예약 {} 토큰 (플랜 {}{})",
                    scan.getScanId(), estimate, plan, billing.team() ? ", 팀 공유 지갑" : "");
            return;
        }

        if (isFreeRescan(owner, scan.getTarget())) {
            log.info("[DAST] 24시간 내 재스캔 — 무과금: {}", scan.getTarget());
            return;
        }
        tokenService.hold(billing.wallet(), plan, HoldUnit.DAST, 1, key, scan.getScanId());
        log.info("[DAST] 스캔 {} 예약 1회 (플랜 {}{})",
                scan.getScanId(), plan, billing.team() ? ", 팀 공유 지갑" : "");
    }

    /** 같은 URL을 24시간 안에 이미 성공적으로 점검했으면 다시 받지 않는다(웹 스캔 한정). */
    private boolean isFreeRescan(User owner, String target) {
        return scanRepository
                .findFirstByUser_UserIdAndTargetAndStatusOrderByCompletedAtDesc(
                        owner.getUserId(), target, ScanStatus.COMPLETED)
                .map(Scan::getCompletedAt)
                .filter(completedAt -> completedAt.isAfter(
                        java.time.LocalDateTime.now().minusHours(TokenPolicy.RESCAN_FREE_WINDOW_HOURS)))
                .isPresent();
    }

    public Scan getScan(UUID id) {
        return scanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Scan not found: " + id));
    }

    /**
     * 스캔 기록을 최신순으로 페이지 단위 조회한다.
     * @param mode null/"ALL"이면 전체, WEBSITE·GITHUB_REPO면 해당 모드만.
     * @param q    대상 URL 부분 검색어(빈 값이면 전체).
     */
    /**
     * 로그인 사용자(ownerId=JWT subject=userId)가 소유한 스캔 기록만 반환.
     * 미로그인/무효 토큰이면 빈 페이지(남의 스캔이 보이지 않도록).
     */
    public Page<Scan> listScans(String ownerId, int page, int size, String mode, String q) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        UUID userId;
        try {
            userId = (ownerId == null || ownerId.isBlank()) ? null : UUID.fromString(ownerId);
        } catch (IllegalArgumentException e) {
            userId = null;
        }
        if (userId == null) return Page.empty(pageable);

        String query = q == null ? "" : q.trim();

        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("ALL")) {
            return scanRepository.findByUser_UserIdAndTargetContainingIgnoreCase(userId, query, pageable);
        }
        ScanMode m;
        try {
            m = ScanMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            return Page.empty(pageable);
        }
        return scanRepository.findByUser_UserIdAndScanModeAndTargetContainingIgnoreCase(userId, m, query, pageable);
    }

    /** 스캔 기록 삭제. 연결된 취약점(scan_id)도 함께 제거한다. */
    @Transactional
    public void deleteScan(UUID id) {
        if (!scanRepository.existsById(id)) {
            throw new IllegalArgumentException("Scan not found: " + id);
        }
        vulnerabilityService.deleteByScanId(id);
        scanRepository.deleteById(id);
    }

    public List<Vulnerability> getVulnerabilities(UUID scanId) {
        return vulnerabilityService.findByScanId(scanId);
    }
}

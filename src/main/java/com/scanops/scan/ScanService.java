package com.scanops.scan;

import com.scanops.user.User;
import com.scanops.user.UserService;
import com.scanops.verify.DomainVerifyService;
import com.scanops.vulnerability.Vulnerability;
import com.scanops.vulnerability.VulnerabilityService;
import lombok.RequiredArgsConstructor;
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
public class ScanService {

    private final ScanRepository scanRepository;
    private final UserService userService;
    private final DomainVerifyService domainVerifyService;
    private final ScanPipelineRunner pipelineRunner;
    private final GithubPipelineRunner githubPipelineRunner;
    private final VulnerabilityService vulnerabilityService;

    /** DAST(웹) 스캔에 도메인 소유권 인증을 강제할지. 데모 등에서 끄려면 false. */
    @Value("${scanops.dast.require-verification:true}")
    private boolean requireDastVerification;

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
        boolean verified = false;
        if (mode == ScanMode.WEBSITE) {
            if (ownerId == null) {
                if (requireDastVerification) {
                    throw new IllegalArgumentException("도메인 소유권 인증을 위해 로그인이 필요합니다.");
                }
            } else {
                verified = domainVerifyService.verifyForScan(ownerId, request.getTargetUrl());
                if (requireDastVerification && !verified) {
                    throw new IllegalArgumentException(
                        "도메인 소유권 인증이 필요합니다. " + DomainVerifyService.VERIFY_PATH
                        + " 파일을 배포하고 인증을 완료해 주세요.");
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

        if (mode == ScanMode.GITHUB_REPO) {
            githubPipelineRunner.run(saved);
        } else {
            pipelineRunner.run(saved);
        }
        return saved;
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
    public Page<Scan> listScans(int page, int size, String mode, String q) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        String query = q == null ? "" : q.trim();

        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("ALL")) {
            return scanRepository.findByTargetContainingIgnoreCase(query, pageable);
        }
        ScanMode m;
        try {
            m = ScanMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            return Page.empty(pageable);
        }
        return scanRepository.findByScanModeAndTargetContainingIgnoreCase(m, query, pageable);
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

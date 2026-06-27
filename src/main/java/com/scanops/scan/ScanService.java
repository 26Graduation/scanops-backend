package com.scanops.scan;

import com.scanops.verify.DomainVerifyService;
import com.scanops.vulnerability.Vulnerability;
import com.scanops.vulnerability.VulnerabilityService;
import lombok.RequiredArgsConstructor;
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

    private final ScanJobRepository scanJobRepository;
    private final DomainVerifyService domainVerifyService;
    private final ScanPipelineRunner pipelineRunner;
    private final GithubPipelineRunner githubPipelineRunner;
    private final VulnerabilityService vulnerabilityService;

    public ScanJob createScan(ScanRequest request) {
        // scanMode 자동 판별: github.com URL이면 GITHUB_REPO
        ScanMode mode = request.getScanMode();
        if (mode == null) {
            mode = request.getTargetUrl().contains("github.com")
                    ? ScanMode.GITHUB_REPO
                    : ScanMode.WEBSITE;
        }

        // GitHub 모드인데 github.com URL이 아닌 경우 즉시 거부 (job 생성 전)
        if (mode == ScanMode.GITHUB_REPO && !request.getTargetUrl().contains("github.com")) {
            throw new IllegalArgumentException(
                "GitHub 레포 분석은 github.com URL만 지원합니다. (예: https://github.com/user/repo)"
            );
        }

        // GitHub URL인데 owner/repo 경로가 없는 경우 (예: https://github.com/)
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

        boolean verified = mode == ScanMode.WEBSITE
                && domainVerifyService.isVerified(request.getTargetUrl());

        ScanJob job = ScanJob.builder()
                .targetUrl(request.getTargetUrl())
                .status(ScanStatus.PENDING)
                .ownerEmail(request.getOwnerEmail())
                .verified(verified)
                .scanMode(mode)
                .build();

        ScanJob saved = scanJobRepository.save(job);

        // 스캔 모드에 따라 다른 파이프라인 실행
        if (mode == ScanMode.GITHUB_REPO) {
            githubPipelineRunner.run(saved);
        } else {
            pipelineRunner.run(saved);
        }
        return saved;
    }

    public ScanJob getScan(UUID id) {
        return scanJobRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Scan job not found: " + id));
    }

    /**
     * 스캔 기록을 최신순으로 페이지 단위 조회한다.
     * @param mode null/"ALL"이면 전체, WEBSITE·GITHUB_REPO면 해당 모드만.
     *             매칭되지 않는 모드(예: 아직 저장되지 않는 PR)는 빈 페이지를 돌려준다.
     * @param q    대상 URL 부분 검색어(빈 값이면 전체).
     */
    public Page<ScanJob> listScans(int page, int size, String mode, String q) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        String query = q == null ? "" : q.trim();

        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("ALL")) {
            return scanJobRepository.findByTargetUrlContainingIgnoreCase(query, pageable);
        }
        ScanMode m;
        try {
            m = ScanMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            // 저장되지 않는 모드(예: 아직 없는 PR)로 필터하면 결과 없음 (전체로 흐르지 않게)
            return Page.empty(pageable);
        }
        return scanJobRepository.findByScanModeAndTargetUrlContainingIgnoreCase(m, query, pageable);
    }

    /** 스캔 기록 삭제. 연결된 취약점(jobId)도 함께 제거한다. */
    @Transactional
    public void deleteScan(UUID id) {
        if (!scanJobRepository.existsById(id)) {
            throw new IllegalArgumentException("Scan job not found: " + id);
        }
        vulnerabilityService.deleteByJobId(id);
        scanJobRepository.deleteById(id);
    }

    public List<Vulnerability> getVulnerabilities(UUID jobId) {
        return vulnerabilityService.findByJobId(jobId);
    }
}

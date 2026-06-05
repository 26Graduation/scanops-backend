package com.scanops.scan;

import com.scanops.ai.AiRouter;
import com.scanops.ai.VulnMetaResult;
import com.scanops.vulnerability.CvssCalculator;
import com.scanops.vulnerability.RiskLevel;
import com.scanops.vulnerability.Vulnerability;
import com.scanops.vulnerability.VulnerabilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * 웹사이트 ZAP 스캔 파이프라인
 *
 * ─ 핵심 설계 ─────────────────────────────────────────────
 * 1. Non-blocking Polling
 *    scan-async 스레드는 ZAP 작업 시작 후 즉시 풀로 반환.
 *    이후 진행률 체크는 zapPollScheduler(별도 스케줄러)가 5초마다 수행.
 *    → scan-async 스레드가 수십 분 묶이는 현상 완전 해소.
 *
 * 2. DB 커넥션 격리
 *    updateJobStatus / updateJobFinished / saveAlerts 는 각각 독립 메서드.
 *    @Async 메서드 내부에 @Transactional이 없으므로 각 save() 호출이
 *    자체 트랜잭션으로 즉시 커밋 후 커넥션 반환.
 *    → HikariCP 커넥션 고갈 방지.
 *
 * 3. 타임아웃 방어
 *    application.yml의 zap.timeout 으로 단계별 최대 대기 시간 설정 가능.
 *    기본값: 스파이더 10분, 액티브 스캔 60분.
 * ────────────────────────────────────────────────────────
 */
@Component
@Slf4j
public class ScanPipelineRunner {

    // ── 의존성 ──────────────────────────────────────────
    private final ScanJobRepository       scanJobRepository;
    private final ZapClient               zapClient;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final CvssCalculator          cvssCalculator;
    private final AiRouter                aiRouter;
    private final Executor                scanExecutor;
    private final ScheduledExecutorService pollScheduler;

    // ── 설정값 ──────────────────────────────────────────
    private static final int POLL_INTERVAL_SEC = 5;

    @Value("${zap.timeout.spider-minutes:10}")
    private int spiderTimeoutMin;

    @Value("${zap.timeout.active-scan-minutes:60}")
    private int activeScanTimeoutMin;

    // ── 생성자 (Qualifier는 생성자 파라미터에서만 동작) ──
    public ScanPipelineRunner(
            ScanJobRepository scanJobRepository,
            ZapClient zapClient,
            VulnerabilityRepository vulnerabilityRepository,
            CvssCalculator cvssCalculator,
            AiRouter aiRouter,
            @Qualifier("scanExecutor") Executor scanExecutor,
            @Qualifier("zapPollScheduler") ScheduledExecutorService pollScheduler) {
        this.scanJobRepository       = scanJobRepository;
        this.zapClient               = zapClient;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.cvssCalculator          = cvssCalculator;
        this.aiRouter                = aiRouter;
        this.scanExecutor            = scanExecutor;
        this.pollScheduler           = pollScheduler;
    }

    // ── 메인 파이프라인 ──────────────────────────────────

    /**
     * scan-async 스레드:
     *   1) 상태 RUNNING 저장 (짧은 트랜잭션)
     *   2) ZAP accessUrl + startSpider 호출 (빠름, ~1초)
     *   3) pollUntilComplete() 로 CompletableFuture 반환 → 스레드 풀로 즉시 반환
     *   4) 이후 체인(startActiveScan → 폴링 → 결과 저장)은 콜백으로 처리
     */
    @Async("scanExecutor")
    public void run(ScanJob job) {
        log.info("[Scan {}] 파이프라인 시작, target={}", job.getId(), job.getTargetUrl());

        // 상태 RUNNING 저장 — 짧은 트랜잭션, 즉시 커넥션 반환
        updateJobStatus(job.getId(), ScanStatus.RUNNING);

        try {
            // ZAP 초기화 + 스파이더 시작 (빠른 HTTP 호출)
            zapClient.accessUrl(job.getTargetUrl());
            String spiderId = zapClient.startSpider(job.getTargetUrl());

            // ── scan-async 스레드 여기서 풀로 반환 ──────────────────
            // 이후는 pollScheduler + scanExecutor 콜백 체인으로 처리
            pollUntilComplete("Spider", () -> zapClient.getSpiderProgress(spiderId), spiderTimeoutMin)

                // 액티브 스캔 시작 → 폴링 (scanExecutor에서 실행, DB·ZAP 호출 모두 포함)
                .thenComposeAsync(v -> {
                    log.info("[Scan {}] 스파이더 완료 → 액티브 스캔 시작", job.getId());
                    String scanId = zapClient.startActiveScan(job.getTargetUrl());
                    return pollUntilComplete("ActiveScan",
                            () -> zapClient.getActiveScanProgress(scanId), activeScanTimeoutMin);
                }, scanExecutor)

                // 결과 수집 및 저장 (scanExecutor에서 실행)
                .thenRunAsync(() -> {
                    log.info("[Scan {}] 액티브 스캔 완료 → 결과 수집 시작", job.getId());
                    List<ZapAlert> alerts = zapClient.getAlerts(job.getTargetUrl());
                    log.info("[Scan {}] ZAP 알림 {}개 수집", job.getId(), alerts.size());
                    saveAlerts(job.getId(), alerts);
                    updateJobFinished(job.getId(), ScanStatus.DONE);
                }, scanExecutor)

                // 예외 처리 — 어느 단계에서 실패해도 FAILED 처리
                .exceptionally(e -> {
                    log.error("[Scan {}] 파이프라인 실패: {}", job.getId(), e.getMessage(), e);
                    updateJobFinished(job.getId(), ScanStatus.FAILED);
                    return null;
                });

        } catch (Exception e) {
            // ZAP 초기화 단계(accessUrl, startSpider) 실패
            log.error("[Scan {}] ZAP 초기화 실패: {}", job.getId(), e.getMessage(), e);
            updateJobFinished(job.getId(), ScanStatus.FAILED);
        }
    }

    // ── Non-blocking 폴링 ────────────────────────────────

    /**
     * pollScheduler 스레드가 5초마다 progressCheck 를 호출.
     * scan-async 스레드는 이 메서드 호출 즉시 풀로 반환됨.
     *
     * @param phase          로그용 단계명 (Spider / ActiveScan)
     * @param progressCheck  ZAP 진행률 조회 람다 (0~100 반환)
     * @param timeoutMinutes 이 시간을 초과하면 예외로 CompletableFuture 완료
     * @return 진행률 100% 도달 시 complete, 타임아웃/오류 시 completeExceptionally
     */
    private CompletableFuture<Void> pollUntilComplete(
            String phase, IntSupplier progressCheck, int timeoutMinutes) {

        CompletableFuture<Void> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes);
        AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();

        ScheduledFuture<?> task = pollScheduler.scheduleWithFixedDelay(() -> {
            // 이미 완료된 Future면 스케줄 취소 후 리턴
            if (future.isDone()) {
                cancelQuietly(taskRef.get());
                return;
            }

            // 타임아웃 초과 검사
            if (System.currentTimeMillis() > deadline) {
                String msg = String.format("[%s] %d분 타임아웃 초과", phase, timeoutMinutes);
                log.error(msg);
                future.completeExceptionally(new RuntimeException(msg));
                cancelQuietly(taskRef.get());
                return;
            }

            // 진행률 폴링
            try {
                int progress = progressCheck.getAsInt();
                log.info("[{}] 진행률: {}%", phase, progress);
                if (progress >= 100) {
                    future.complete(null);
                    cancelQuietly(taskRef.get());
                }
            } catch (Exception e) {
                log.error("[{}] 폴링 중 오류: {}", phase, e.getMessage());
                future.completeExceptionally(e);
                cancelQuietly(taskRef.get());
            }
        }, POLL_INTERVAL_SEC, POLL_INTERVAL_SEC, TimeUnit.SECONDS);

        taskRef.set(task);
        return future;
    }

    private void cancelQuietly(ScheduledFuture<?> task) {
        if (task != null && !task.isDone()) {
            task.cancel(false);
        }
    }

    // ── DB 조작 (각각 독립 트랜잭션, 커넥션 즉시 반환) ─────

    /**
     * 스캔 상태만 변경 — save() 호출이 자체 트랜잭션으로 처리됨.
     * @Transactional 없이도 Spring Data Repository의 기본 트랜잭션이 적용.
     */
    private void updateJobStatus(UUID jobId, ScanStatus status) {
        ScanJob job = scanJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("ScanJob not found: " + jobId));
        job.setStatus(status);
        scanJobRepository.save(job);
        log.info("[Scan {}] 상태 변경 → {}", jobId, status);
    }

    /**
     * 스캔 완료/실패 처리 — status + finishedAt 저장 후 커넥션 즉시 반환.
     */
    private void updateJobFinished(UUID jobId, ScanStatus status) {
        ScanJob job = scanJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("ScanJob not found: " + jobId));
        job.setStatus(status);
        job.setFinishedAt(LocalDateTime.now());
        scanJobRepository.save(job);
        log.info("[Scan {}] 파이프라인 종료 → 최종 상태: {}", jobId, status);
    }

    /**
     * ZAP 알림을 Vulnerability 로 변환하여 저장.
     * 각 save() 가 독립 트랜잭션 → 한 건 저장 후 커넥션 즉시 반환.
     */
    private void saveAlerts(UUID jobId, List<ZapAlert> alerts) {
        for (ZapAlert alert : alerts) {
            Vulnerability vuln = buildVulnerability(jobId, alert);

            // AI 메타 생성 (실패해도 스캔 자체는 계속)
            if (needsAiMeta(vuln)) {
                try {
                    VulnMetaResult meta = aiRouter.generateMeta(
                            alert.getAlert(), alert.getDescription(), alert.getSolution());
                    vuln.setSummary(meta.summary());
                    vuln.setDescription(meta.description());
                    vuln.setSolution(meta.solution());
                } catch (Exception e) {
                    log.warn("[Scan {}] AI 메타 생성 실패 '{}': {}", jobId, alert.getAlert(), e.getMessage());
                }
            }

            vulnerabilityRepository.save(vuln); // 독립 트랜잭션으로 즉시 커밋
        }
    }

    // ── 내부 유틸 ────────────────────────────────────────

    private Vulnerability buildVulnerability(UUID jobId, ZapAlert alert) {
        RiskLevel riskLevel = mapRiskLevel(alert.getRisk());
        double    cvssScore  = cvssCalculator.calculate(riskLevel, alert.getAlert());
        String    cvssVector = cvssCalculator.generateVector(riskLevel, alert.getAlert());

        return Vulnerability.builder()
                .jobId(jobId)
                .vulnType(alert.getAlert())
                .url(alert.getUrl())
                .parameter(alert.getParam())
                .riskLevel(riskLevel)
                .cvssScore(cvssScore)
                .cvssVector(cvssVector)
                .description(alert.getDescription())
                .solution(alert.getSolution())
                .build();
    }

    private boolean needsAiMeta(Vulnerability vuln) {
        return vuln.getRiskLevel() != RiskLevel.INFORMATIONAL;
    }

    private RiskLevel mapRiskLevel(String risk) {
        if (risk == null) return RiskLevel.INFORMATIONAL;
        return switch (risk.toLowerCase()) {
            case "high"   -> RiskLevel.HIGH;
            case "medium" -> RiskLevel.MEDIUM;
            case "low"    -> RiskLevel.LOW;
            default       -> RiskLevel.INFORMATIONAL;
        };
    }
}

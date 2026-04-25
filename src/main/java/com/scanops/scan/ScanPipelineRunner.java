package com.scanops.scan;

import com.scanops.vulnerability.CvssCalculator;
import com.scanops.vulnerability.RiskLevel;
import com.scanops.vulnerability.Vulnerability;
import com.scanops.vulnerability.VulnerabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScanPipelineRunner {

    private final ScanJobRepository scanJobRepository;
    private final ZapClient zapClient;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final CvssCalculator cvssCalculator;

    @Async("scanExecutor")
    public void run(ScanJob job) {
        log.info("Scan pipeline starting for job {}, target={}", job.getId(), job.getTargetUrl());
        job.setStatus(ScanStatus.RUNNING);
        scanJobRepository.save(job);

        try {
            zapClient.accessUrl(job.getTargetUrl());

            String spiderId = zapClient.startSpider(job.getTargetUrl());
            waitForCompletion("Spider", () -> zapClient.getSpiderProgress(spiderId));

            String scanId = zapClient.startActiveScan(job.getTargetUrl());
            waitForCompletion("ActiveScan", () -> zapClient.getActiveScanProgress(scanId));

            List<ZapAlert> alerts = zapClient.getAlerts(job.getTargetUrl());
            log.info("Job {} found {} alerts", job.getId(), alerts.size());

            for (ZapAlert alert : alerts) {
                vulnerabilityRepository.save(buildVulnerability(job.getId(), alert));
            }

            job.setStatus(ScanStatus.DONE);
        } catch (Exception e) {
            log.error("Scan pipeline failed for job {}: {}", job.getId(), e.getMessage());
            job.setStatus(ScanStatus.FAILED);
        }

        job.setFinishedAt(LocalDateTime.now());
        scanJobRepository.save(job);
        log.info("Scan pipeline finished for job {} with status {}", job.getId(), job.getStatus());
    }

    private void waitForCompletion(String phase, IntSupplier progressCheck) {
        int progress = 0;
        while (progress < 100) {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(phase + " interrupted", e);
            }
            progress = progressCheck.getAsInt();
            log.debug("{} progress: {}%", phase, progress);
        }
    }

    private Vulnerability buildVulnerability(UUID jobId, ZapAlert alert) {
        RiskLevel riskLevel = mapRiskLevel(alert.getRisk());
        double cvssScore = cvssCalculator.calculate(riskLevel, alert.getAlert());
        String cvssVector = cvssCalculator.generateVector(riskLevel, alert.getAlert());

        return Vulnerability.builder()
                .jobId(jobId)
                .vulnType(alert.getAlert())
                .url(alert.getUrl())
                .parameter(alert.getParam())
                .riskLevel(riskLevel)
                .cvssScore(cvssScore)
                .cvssVector(cvssVector)
                .build();
    }

    private RiskLevel mapRiskLevel(String risk) {
        if (risk == null) return RiskLevel.INFORMATIONAL;
        return switch (risk.toLowerCase()) {
            case "high" -> RiskLevel.HIGH;
            case "medium" -> RiskLevel.MEDIUM;
            case "low" -> RiskLevel.LOW;
            default -> RiskLevel.INFORMATIONAL;
        };
    }
}

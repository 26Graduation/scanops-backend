package com.scanops.scan;

import com.scanops.ai.AiRouter;
import com.scanops.ai.VulnMetaResult;
import com.scanops.vulnerability.CvssCalculator;
import com.scanops.vulnerability.Severity;
import com.scanops.vulnerability.Vulnerability;
import com.scanops.vulnerability.VulnerabilityRepository;
import com.scanops.vulnerability.VulnerabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntSupplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScanPipelineRunner {

    private final ScanRepository scanRepository;
    private final ZapClient zapClient;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final VulnerabilityService vulnerabilityService;
    private final CvssCalculator cvssCalculator;
    private final AiRouter aiRouter;

    @Async("scanExecutor")
    public void run(Scan scan) {
        log.info("Scan pipeline starting for scan {}, target={}", scan.getScanId(), scan.getTarget());
        scan.setStatus(ScanStatus.RUNNING);
        scan.setStartedAt(LocalDateTime.now());
        scanRepository.save(scan);

        try {
            zapClient.accessUrl(scan.getTarget());

            String spiderId = zapClient.startSpider(scan.getTarget());
            waitForCompletion("Spider", () -> zapClient.getSpiderProgress(spiderId));

            String activeScanId = zapClient.startActiveScan(scan.getTarget());
            waitForCompletion("ActiveScan", () -> zapClient.getActiveScanProgress(activeScanId));

            List<ZapAlert> alerts = zapClient.getAlerts(scan.getTarget());
            log.info("Scan {} found {} alerts", scan.getScanId(), alerts.size());

            for (ZapAlert alert : alerts) {
                Vulnerability vuln = buildVulnerability(scan, alert);
                if (needsAiMeta(vuln)) {
                    try {
                        VulnMetaResult meta = aiRouter.generateMeta(
                                alert.getAlert(), alert.getDescription(), alert.getSolution());
                        vuln.setCause(meta.description());
                        vuln.setSolution(meta.solution());
                    } catch (Exception e) {
                        log.warn("AI meta generation failed for '{}', will retry on next startup: {}",
                                alert.getAlert(), e.getMessage());
                    }
                }
                vulnerabilityRepository.save(vuln);
            }

            vulnerabilityService.updateScanAggregates(scan);
            scan.setStatus(ScanStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Scan pipeline failed for scan {}: {}", scan.getScanId(), e.getMessage());
            scan.setStatus(ScanStatus.FAILED);
        }

        scan.setCompletedAt(LocalDateTime.now());
        scanRepository.save(scan);
        log.info("Scan pipeline finished for scan {} with status {}", scan.getScanId(), scan.getStatus());
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

    private Vulnerability buildVulnerability(Scan scan, ZapAlert alert) {
        Severity severity = mapSeverity(alert.getRisk());
        double cvssScore = cvssCalculator.calculate(severity, alert.getAlert());
        String cvssVector = cvssCalculator.generateVector(severity, alert.getAlert());

        return Vulnerability.builder()
                .scan(scan)
                .vulnType(alert.getAlert())
                .url(alert.getUrl())
                .parameter(alert.getParam())
                .severity(severity)
                .cvssScore(cvssScore)
                .cvssVector(cvssVector)
                .cause(alert.getDescription())
                .solution(alert.getSolution())
                .build();
    }

    private boolean needsAiMeta(Vulnerability vuln) {
        return vuln.getSeverity() != Severity.INFORMATIONAL;
    }

    private Severity mapSeverity(String risk) {
        if (risk == null) return Severity.INFORMATIONAL;
        return switch (risk.toLowerCase()) {
            case "high" -> Severity.HIGH;
            case "medium" -> Severity.MEDIUM;
            case "low" -> Severity.LOW;
            default -> Severity.INFORMATIONAL;
        };
    }
}

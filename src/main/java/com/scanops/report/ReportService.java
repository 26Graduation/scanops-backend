package com.scanops.report;

import com.scanops.vulnerability.Vulnerability;
import com.scanops.vulnerability.VulnerabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final VulnerabilityService vulnerabilityService;

    public ReportResponse getReport(UUID scanId) {
        List<Vulnerability> vulns = vulnerabilityService.findByScanId(scanId);

        double maxCvss = vulns.stream()
                .map(Vulnerability::getCvssScore)
                .filter(s -> s != null)
                .max(Comparator.naturalOrder())
                .orElse(0.0);

        return new ReportResponse(scanId.toString(), maxCvss, vulns);
    }
}

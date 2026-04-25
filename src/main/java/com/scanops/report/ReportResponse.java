package com.scanops.report;

import com.scanops.vulnerability.Vulnerability;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ReportResponse {
    private String targetUrl;
    private double maxCvssScore;
    private List<Vulnerability> vulnerabilities;
}

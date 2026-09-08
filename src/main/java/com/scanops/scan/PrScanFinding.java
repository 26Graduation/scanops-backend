package com.scanops.scan;

import java.util.List;

public record PrScanFinding(
        String filename,
        boolean detected,
        String vulnerability,
        String severity,
        Double cvssScore,
        String attack,
        String fix,
        List<ScanopsModelClient.CveReference> cveReferences,
        Integer diffLine,
        String source,
        String reason,
        String aiPrompt
) {}

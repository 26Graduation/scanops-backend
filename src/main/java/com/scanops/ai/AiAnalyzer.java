package com.scanops.ai;

import com.scanops.vulnerability.Vulnerability;

public interface AiAnalyzer {
    AiModel getModel();
    String analyze(Vulnerability vulnerability);
}

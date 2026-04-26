package com.scanops.ai;

import com.scanops.vulnerability.Vulnerability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiRouter {

    private final List<AiAnalyzer> analyzers;

    /**
     * Routes to the first available analyzer in priority order: GPT → CLAUDE → GEMINI → CUSTOM.
     * Falls back to the next analyzer if the current one throws.
     */
    public String analyze(Vulnerability vulnerability) {
        for (AiModel model : List.of(AiModel.GPT, AiModel.CLAUDE, AiModel.GEMINI, AiModel.CUSTOM)) {
            AiAnalyzer analyzer = findAnalyzer(model);
            if (analyzer == null) continue;
            try {
                String result = analyzer.analyze(vulnerability);
                vulnerability.setAiModel(model);
                return result;
            } catch (Exception e) {
                log.warn("Analyzer {} failed, trying next: {}", model, e.getMessage());
            }
        }
        throw new RuntimeException("All AI analyzers failed");
    }

    public VulnMetaResult generateMeta(String vulnType) {
        for (AiModel model : List.of(AiModel.GPT, AiModel.CLAUDE, AiModel.GEMINI, AiModel.CUSTOM)) {
            AiAnalyzer analyzer = findAnalyzer(model);
            if (analyzer == null) continue;
            try {
                return analyzer.generateMeta(vulnType);
            } catch (Exception e) {
                log.warn("Analyzer {} generateMeta failed, trying next: {}", model, e.getMessage());
            }
        }
        throw new RuntimeException("All AI analyzers failed for generateMeta");
    }

    private AiAnalyzer findAnalyzer(AiModel model) {
        return analyzers.stream()
                .filter(a -> a.getModel() == model)
                .findFirst()
                .orElse(null);
    }
}

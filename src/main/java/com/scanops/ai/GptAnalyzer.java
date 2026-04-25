package com.scanops.ai;

import com.scanops.vulnerability.Vulnerability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GptAnalyzer implements AiAnalyzer {

    private final WebClient.Builder webClientBuilder;

    @Value("${ai.openai.api-key:}")
    private String apiKey;

    @Value("${ai.openai.model:gpt-4o-mini}")
    private String model;

    @Override
    public AiModel getModel() {
        return AiModel.GPT;
    }

    @Override
    public String analyze(Vulnerability vulnerability) {
        if (apiKey.isBlank()) throw new IllegalStateException("OpenAI API key not configured");

        String prompt = buildPrompt(vulnerability);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        Map<?, ?> response = webClientBuilder.baseUrl("https://api.openai.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build()
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        return extractContent(response);
    }

    private String buildPrompt(Vulnerability v) {
        return String.format(
                "다음 웹 취약점을 분석하고 한국어로 대응 방안을 설명해주세요.\n" +
                "유형: %s\nURL: %s\n파라미터: %s\nCVSS: %s",
                v.getVulnType(), v.getUrl(), v.getParameter(), v.getCvssVector()
        );
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<?, ?> response) {
        List<?> choices = (List<?>) response.get("choices");
        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
        Map<?, ?> message = (Map<?, ?>) choice.get("message");
        return (String) message.get("content");
    }
}

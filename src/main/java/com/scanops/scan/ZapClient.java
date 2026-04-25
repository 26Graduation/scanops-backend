package com.scanops.scan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ZapClient {

    private final WebClient client;
    private final String zapApiKey;

    public ZapClient(
            WebClient.Builder webClientBuilder,
            @Value("${zap.host}") String zapHost,
            @Value("${zap.api-key}") String zapApiKey) {
        this.client = webClientBuilder
                .baseUrl(zapHost)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build())
                .build();
        this.zapApiKey = zapApiKey;
    }

    public void accessUrl(String targetUrl) {
        log.info("Seeding ZAP scan tree with {}", targetUrl);
        client.get()
                .uri(uri -> uri.path("/JSON/core/action/accessUrl/")
                        .queryParam("apikey", zapApiKey)
                        .queryParam("url", targetUrl)
                        .queryParam("followRedirects", "true")
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    public String startSpider(String targetUrl) {
        log.info("Starting ZAP spider for {}", targetUrl);
        Map<?, ?> response = client.get()
                .uri(uri -> uri.path("/JSON/spider/action/scan/")
                        .queryParam("apikey", zapApiKey)
                        .queryParam("url", targetUrl)
                        .queryParam("recurse", "true")
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        return String.valueOf(response.get("scan"));
    }

    public int getSpiderProgress(String scanId) {
        Map<?, ?> response = client.get()
                .uri(uri -> uri.path("/JSON/spider/view/status/")
                        .queryParam("apikey", zapApiKey)
                        .queryParam("scanId", scanId)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        return Integer.parseInt(String.valueOf(response.get("status")));
    }

    public String startActiveScan(String targetUrl) {
        log.info("Starting ZAP active scan for {}", targetUrl);
        Map<?, ?> response = client.get()
                .uri(uri -> uri.path("/JSON/ascan/action/scan/")
                        .queryParam("apikey", zapApiKey)
                        .queryParam("url", targetUrl)
                        .queryParam("recurse", "true")
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        return String.valueOf(response.get("scan"));
    }

    public int getActiveScanProgress(String scanId) {
        Map<?, ?> response = client.get()
                .uri(uri -> uri.path("/JSON/ascan/view/status/")
                        .queryParam("apikey", zapApiKey)
                        .queryParam("scanId", scanId)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        return Integer.parseInt(String.valueOf(response.get("status")));
    }

    @SuppressWarnings("unchecked")
    public List<ZapAlert> getAlerts(String targetUrl) {
        log.info("Fetching ZAP alerts for {}", targetUrl);
        Map<?, ?> response = client.get()
                .uri(uri -> uri.path("/JSON/core/view/alerts/")
                        .queryParam("apikey", zapApiKey)
                        .queryParam("baseurl", targetUrl)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        List<?> rawAlerts = (List<?>) response.get("alerts");
        if (rawAlerts == null) return List.of();

        return rawAlerts.stream()
                .map(a -> {
                    Map<?, ?> m = (Map<?, ?>) a;
                    return new ZapAlert(
                            str(m, "alert"),
                            str(m, "risk"),
                            str(m, "url"),
                            str(m, "param"),
                            str(m, "description"),
                            str(m, "solution"));
                })
                .collect(Collectors.toList());
    }

    private String str(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}

package com.scanops.scan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class ZapClient {

    private final WebClient.Builder webClientBuilder;
    private final ScanJobRepository scanJobRepository;

    @Value("${zap.host}")
    private String zapHost;

    @Value("${zap.api-key}")
    private String zapApiKey;

    @Async
    public void startScanAsync(ScanJob job) {
        WebClient client = webClientBuilder.baseUrl(zapHost).build();

        job.setStatus(ScanStatus.RUNNING);
        scanJobRepository.save(job);

        try {
            client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/JSON/spider/action/scan/")
                            .queryParam("apikey", zapApiKey)
                            .queryParam("url", job.getTargetUrl())
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            job.setStatus(ScanStatus.DONE);
        } catch (Exception e) {
            log.error("ZAP scan failed for job {}: {}", job.getId(), e.getMessage());
            job.setStatus(ScanStatus.FAILED);
        }

        scanJobRepository.save(job);
    }
}

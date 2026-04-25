package com.scanops.scan;

import com.scanops.verify.DomainVerifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScanService {

    private final ScanJobRepository scanJobRepository;
    private final DomainVerifyService domainVerifyService;
    private final ZapClient zapClient;

    public ScanJob createScan(ScanRequest request) {
        boolean verified = domainVerifyService.isVerified(request.getTargetUrl());

        ScanJob job = ScanJob.builder()
                .targetUrl(request.getTargetUrl())
                .status(ScanStatus.PENDING)
                .ownerEmail(request.getOwnerEmail())
                .verified(verified)
                .build();

        ScanJob saved = scanJobRepository.save(job);

        if (verified) {
            zapClient.startScanAsync(saved);
        }

        return saved;
    }

    public ScanJob getScan(UUID id) {
        return scanJobRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Scan job not found: " + id));
    }

    public List<ScanJob> listScans() {
        return scanJobRepository.findAll();
    }
}

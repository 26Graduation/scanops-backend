package com.scanops.scan;

import com.scanops.vulnerability.Vulnerability;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/scans")
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scanService;

    @PostMapping
    public ResponseEntity<ScanJob> createScan(@Valid @RequestBody ScanRequest request) {
        return ResponseEntity.ok(scanService.createScan(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScanJob> getScan(@PathVariable UUID id) {
        return ResponseEntity.ok(scanService.getScan(id));
    }

    @GetMapping("/{id}/vulnerabilities")
    public ResponseEntity<List<Vulnerability>> getVulnerabilities(@PathVariable UUID id) {
        return ResponseEntity.ok(scanService.getVulnerabilities(id));
    }

    @GetMapping
    public ResponseEntity<List<ScanJob>> listScans() {
        return ResponseEntity.ok(scanService.listScans());
    }
}

package com.scanops.scan;

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

    @GetMapping
    public ResponseEntity<List<ScanJob>> listScans() {
        return ResponseEntity.ok(scanService.listScans());
    }
}

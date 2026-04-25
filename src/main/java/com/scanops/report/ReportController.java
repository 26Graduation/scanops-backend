package com.scanops.report;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/{jobId}")
    public ResponseEntity<ReportResponse> getReport(@PathVariable UUID jobId) {
        return ResponseEntity.ok(reportService.getReport(jobId));
    }
}

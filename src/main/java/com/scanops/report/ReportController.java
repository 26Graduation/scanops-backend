package com.scanops.report;

import lombok.RequiredArgsConstructor;
import com.scanops.auth.JwtService;
import com.scanops.scan.ScanService;
import com.scanops.scan.Scan;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final ScanService scanService;
    private final JwtService jwtService;

    @GetMapping("/{jobId}")
    public ResponseEntity<ReportResponse> getReport(@PathVariable UUID jobId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return ResponseEntity.status(401).build();
        String owner;
        try { owner = jwtService.parse(authorization.substring(7)).getSubject(); }
        catch (Exception e) { return ResponseEntity.status(401).build(); }
        if (owner == null) return ResponseEntity.status(401).build();
        Scan scan = scanService.getScan(jobId);
        if (scan.getUser() == null || !owner.equals(scan.getUser().getUserId().toString())) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(reportService.getReport(jobId));
    }
}

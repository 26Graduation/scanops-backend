package com.scanops.scan;

import com.scanops.vulnerability.Vulnerability;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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

    /** Bean Validation 실패 (400) → 첫 번째 필드 오류 메시지 반환 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse("입력값이 올바르지 않습니다");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** URL/GitHub URL 형식 오류 (400) */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScanJob> getScan(@PathVariable UUID id) {
        return ResponseEntity.ok(scanService.getScan(id));
    }

    @GetMapping("/{id}/vulnerabilities")
    public ResponseEntity<List<Vulnerability>> getVulnerabilities(@PathVariable UUID id) {
        return ResponseEntity.ok(scanService.getVulnerabilities(id));
    }

    /**
     * 스캔 기록 페이지 조회 (최신순). 기본 10개씩.
     * 예: /api/scans?page=0&size=10&mode=WEBSITE&q=example.com
     */
    @GetMapping
    public ResponseEntity<Page<ScanJob>> listScans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String mode,
            @RequestParam(defaultValue = "") String q) {
        return ResponseEntity.ok(scanService.listScans(page, size, mode, q));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteScan(@PathVariable UUID id) {
        scanService.deleteScan(id);
        return ResponseEntity.noContent().build();
    }
}

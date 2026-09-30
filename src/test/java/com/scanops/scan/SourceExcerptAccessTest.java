package com.scanops.scan;

import com.scanops.auth.JwtService;
import com.scanops.report.ReportController;
import com.scanops.report.ReportService;
import com.scanops.user.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceExcerptAccessTest {
    @Test void sourceBearingEndpointsRequireTheScanOwner() {
        ScanService service = mock(ScanService.class);
        JwtService jwt = mock(JwtService.class);
        ReportService reports = mock(ReportService.class);
        ScanController controller = new ScanController(service, jwt);
        ReportController reportController = new ReportController(reports, service, jwt);
        UUID scanId = UUID.randomUUID(), owner = UUID.randomUUID();
        Scan scan = Scan.builder().user(User.builder().userId(owner).build()).build();
        when(service.getScan(scanId)).thenReturn(scan);
        when(service.getVulnerabilities(scanId)).thenReturn(List.of());
        Claims claims = mock(Claims.class);
        when(jwt.parse("token")).thenReturn(claims);
        assertEquals(401, controller.getVulnerabilities(scanId, null).getStatusCode().value());
        assertEquals(401, reportController.getReport(scanId, null).getStatusCode().value());
        when(claims.getSubject()).thenReturn(UUID.randomUUID().toString());
        assertEquals(404, controller.getVulnerabilities(scanId, "Bearer token").getStatusCode().value());
        assertEquals(404, reportController.getReport(scanId, "Bearer token").getStatusCode().value());
        verify(service, never()).getVulnerabilities(any());
        verify(reports, never()).getReport(any());
        when(claims.getSubject()).thenReturn(owner.toString());
        assertEquals(200, controller.getVulnerabilities(scanId, "Bearer token").getStatusCode().value());
        assertEquals(200, reportController.getReport(scanId, "Bearer token").getStatusCode().value());
    }
}

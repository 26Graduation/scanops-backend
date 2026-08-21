package com.scanops.scan;

import com.scanops.subscription.Plan;
import com.scanops.subscription.SubscriptionService;
import com.scanops.token.BillingResolver;
import com.scanops.token.TokenService;
import com.scanops.token.TokenWallet;
import com.scanops.user.User;
import com.scanops.user.UserService;
import com.scanops.verify.DomainVerifyService;
import com.scanops.vulnerability.VulnerabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScanOps 자체 프론트엔드 도메인(scanops-frontend.vercel.app)은 로그인한 사용자에 한해
 * 소유권 인증만 생략된다 — 로그인 자체는 다른 도메인과 동일하게 여전히 필요하다.
 * 다른 도메인은 기존 인증 로직이 그대로 유지돼야 한다(회귀 방지).
 */
class ScanServiceSelfScanTest {

    private ScanRepository scanRepository;
    private DomainVerifyService domainVerifyService;
    private ScanService scanService;

    @BeforeEach
    void setUp() {
        scanRepository = mock(ScanRepository.class);
        UserService userService = mock(UserService.class);
        domainVerifyService = mock(DomainVerifyService.class);
        BillingResolver billingResolver = mock(BillingResolver.class);

        scanService = new ScanService(
                scanRepository,
                userService,
                domainVerifyService,
                mock(ScanPipelineRunner.class),
                mock(GithubPipelineRunner.class),
                mock(VulnerabilityService.class),
                mock(GithubScanService.class),
                mock(SubscriptionService.class),
                mock(TokenService.class),
                billingResolver
        );
        ReflectionTestUtils.setField(scanService, "requireDastVerification", true);
        ReflectionTestUtils.setField(scanService, "selfScanDomain", "scanops-frontend.vercel.app");

        User owner = User.builder().userId(UUID.randomUUID()).email("tester@example.com").build();
        when(userService.findOrCreateByEmail(any())).thenReturn(owner);
        when(scanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingResolver.resolve(any()))
                .thenReturn(new BillingResolver.Billing(Plan.FREE, mock(TokenWallet.class), false));
    }

    private ScanRequest request(String url) {
        ScanRequest req = new ScanRequest();
        req.setTargetUrl(url);
        req.setScanMode(ScanMode.WEBSITE);
        req.setOwnerEmail("tester@example.com");
        return req;
    }

    @Test
    @DisplayName("자체 서비스 도메인은 로그인한 사용자라면 소유권 인증 없이 통과한다")
    void selfScanDomainBypassesVerificationWhenLoggedIn() {
        ScanRequest req = request("https://scanops-frontend.vercel.app/");
        when(domainVerifyService.extractDomain(req.getTargetUrl())).thenReturn("scanops-frontend.vercel.app");

        Scan result = scanService.createScan(req, UUID.randomUUID().toString()); // 로그인 상태

        assertTrue(result.isVerified());
        verify(domainVerifyService, never()).verifyForScan(any(), any());
    }

    @Test
    @DisplayName("자체 서비스 도메인이어도 로그인 자체는 여전히 필요하다")
    void selfScanDomainStillRequiresLogin() {
        ScanRequest req = request("https://scanops-frontend.vercel.app/");

        assertThrows(IllegalArgumentException.class, () -> scanService.createScan(req, null));
        verify(domainVerifyService, never()).extractDomain(any());
    }

    @Test
    @DisplayName("다른 도메인은 로그인해도 여전히 소유권 인증이 필요하다 (회귀 방지)")
    void otherDomainStillRequiresVerification() {
        ScanRequest req = request("https://example.com/");
        when(domainVerifyService.extractDomain(req.getTargetUrl())).thenReturn("example.com");

        assertThrows(IllegalArgumentException.class,
                () -> scanService.createScan(req, UUID.randomUUID().toString()));
    }
}

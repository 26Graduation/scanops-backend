package com.scanops.github;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GitHub App 설치 진입점.
 *
 * <p>프라이빗 레포/PR 분석은 사용자가 이 URL로 App을 설치(레포 선택 = 소유권 증명)하면
 * 이후 스캔 시 서버가 {@link GithubAppService#tokenForRepo}로 그 레포 전용 단기 토큰을
 * 즉석 발급해 접근한다. 사용자 PAT는 받지 않고, 별도 테이블도 만들지 않는다.
 */
@RestController
@RequestMapping("/api/github/app")
@RequiredArgsConstructor
public class GithubAppController {

    private final GithubAppService githubAppService;

    /** 설치 화면 URL + App 설정 여부. */
    @GetMapping("/install-url")
    public ResponseEntity<?> installUrl() {
        return ResponseEntity.ok(Map.of(
                "configured", githubAppService.isConfigured(),
                "url", githubAppService.installUrl()
        ));
    }
}

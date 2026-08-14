package com.scanops.scan;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * @param ownerGithubId 레포 소유자의 GitHub 숫자 id. 과금 대상 계정을 찾는 데 쓴다.
 *                      GitHub Action에서 {@code github.repository_owner_id}로 넘기면 되고,
 *                      없으면 무과금으로 스캔만 수행한다.
 */
public record PrScanRequest(
        String repo,
        int prNumber,
        @JsonAlias({"owner_github_id", "repository_owner_id"})
        String ownerGithubId,
        List<PrFile> files
) {
    public record PrFile(
            String filename,
            String content,
            String patch
    ) {}
}

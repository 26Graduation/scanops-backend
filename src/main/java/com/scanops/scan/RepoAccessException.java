package com.scanops.scan;

/**
 * GitHub 레포에 접근할 수 없을 때(프라이빗 + ScanOps App 미설치 등).
 * 메시지는 사용자에게 그대로 보여줄 안내 문구를 담는다.
 */
public class RepoAccessException extends RuntimeException {
    public RepoAccessException(String message) {
        super(message);
    }
}

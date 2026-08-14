package com.scanops.token;

/** 플랜별 동시 실행 스캔 수를 넘겼을 때. GPU 동시성 보호용. HTTP 429로 매핑한다. */
public class ConcurrentScanLimitException extends RuntimeException {

    private final int limit;

    public ConcurrentScanLimitException(int limit) {
        super(String.format(
                "현재 플랜은 스캔을 동시에 %d개까지 실행할 수 있어요. "
                + "진행 중인 스캔이 끝나면 다시 시도해 주세요.", limit));
        this.limit = limit;
    }

    public int getLimit() { return limit; }
}

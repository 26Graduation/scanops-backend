package com.scanops.token;

public enum HoldStatus {
    /** 예약됨 — 스캔 진행 중. */
    HELD,
    /** 실사용량으로 정산 완료. */
    COMMITTED,
    /** 실패·무과금으로 전액 반환. */
    RELEASED
}

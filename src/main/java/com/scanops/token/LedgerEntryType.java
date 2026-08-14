package com.scanops.token;

public enum LedgerEntryType {
    /** 결제주기 구독 토큰 지급. */
    GRANT,
    /** 가입 보너스(웹사이트 점검 1회, DAST 횟수). 계정당 평생 1회. */
    SIGNUP_BONUS,
    /** 7일 무료체험 지급 (PRO 전용, 월 지급량의 25%). */
    TRIAL_GRANT,
    /** 5,000원 = 3,000토큰 충전. */
    PURCHASE,
    /** 스캔 시작 시 예약. */
    HOLD,
    /** 스캔 완료 시 실사용량 확정 (예약 초과분은 반환). */
    COMMIT,
    /** 스캔 실패·무과금으로 예약 전액 반환. */
    RELEASE,
    /** 주기 종료로 구독분 소멸, 충전분 유효기간 만료. */
    EXPIRE,
    /** 운영자 수동 조정. */
    ADJUST
}

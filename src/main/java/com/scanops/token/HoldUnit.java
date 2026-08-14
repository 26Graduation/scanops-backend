package com.scanops.token;

/**
 * 예약/원장 한 건이 어느 통화 단위인지. 같은 지갑 행이 토큰(SAST·액션)과
 * DAST 횟수를 둘 다 갖고 있어서, {@code amount} 컬럼 하나를 두 단위가 같이 쓰기 때문에
 * 반드시 구분해야 한다.
 */
public enum HoldUnit {
    /** SAST·GitHub App(액션). 단위: 토큰. */
    TOKEN,
    /** 웹사이트 점검. 단위: 횟수(항상 1). */
    DAST
}

package com.scanops.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenPolicyTest {

    @Test
    @DisplayName("소스코드 과금은 1,000줄 단위 올림 + 최소 300토큰")
    void tokensForLines() {
        assertEquals(0,     TokenPolicy.tokensForLines(0));
        assertEquals(300,   TokenPolicy.tokensForLines(1));
        assertEquals(300,   TokenPolicy.tokensForLines(990));
        assertEquals(300,   TokenPolicy.tokensForLines(1_000));
        assertEquals(600,   TokenPolicy.tokensForLines(1_001));
        assertEquals(1_500, TokenPolicy.tokensForLines(4_200));
    }

    @Test
    @DisplayName("가격표 앵커: 소스 1만 줄 = 3,000토큰 = 5,000원")
    void priceAnchorForSourceCode() {
        long tokens = TokenPolicy.tokensForLines(10_000);
        assertEquals(3_000, tokens);
        assertEquals(5_000, TokenPolicy.toKrw(tokens));
    }

    @Test
    @DisplayName("가격표 앵커: 웹 점검 3회 = 5,000원 (DAST는 토큰이 아니라 횟수 단위)")
    void priceAnchorForWebsiteScan() {
        assertEquals(3, TokenPolicy.DAST_PURCHASE_COUNT);
        assertEquals(5_000, TokenPolicy.DAST_PURCHASE_PRICE_KRW);
    }

    @Test
    @DisplayName("PR 스캔은 100줄 단위, 최소 100토큰 — 줄당 단가는 소스코드와 동일")
    void prScanUsesFinerUnit() {
        assertEquals(100, TokenPolicy.tokensForPrLines(20));     // 하한
        assertEquals(100, TokenPolicy.tokensForPrLines(300));    // 90 → 하한 100
        assertEquals(300, TokenPolicy.tokensForPrLines(1_000));  // 1,000줄 = 300토큰 (SAST와 동일)
        assertEquals(330, TokenPolicy.tokensForPrLines(1_001));
    }
}

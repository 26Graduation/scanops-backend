package com.scanops.token;

/** 잔액 부족으로 스캔을 시작할 수 없을 때. HTTP 402로 매핑한다. */
public class InsufficientTokensException extends RuntimeException {

    private final long required;
    private final long available;

    public InsufficientTokensException(long required, long available) {
        super(String.format(
                "토큰이 부족합니다. 이 스캔에 %,d 토큰이 필요한데 잔액은 %,d 토큰이에요. "
                + "충전(%,d원 = %,d토큰)하거나 플랜을 업그레이드해 주세요.",
                required, available, TokenPolicy.PURCHASE_PRICE_KRW, TokenPolicy.PURCHASE_TOKENS));
        this.required = required;
        this.available = available;
    }

    public long getRequired()  { return required; }
    public long getAvailable() { return available; }
}

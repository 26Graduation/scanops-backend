package com.scanops.token;

/**
 * 스캔 과금 정책 — DAST(웹)는 <b>횟수</b>, SAST·GitHub App(액션)은 <b>토큰</b>으로 단위가 다르다.
 * 두 단위의 단가/환율을 한 곳에서 관리한다.
 *
 * <p><b>토큰 환율</b>: 5,000원 = 3,000 토큰 (1토큰 ≈ 1.67원). 소스코드 1만 줄 = 5,000원
 * → 소스 1,000줄 = 300 토큰(0.3 토큰/줄). GitHub App(액션)도 같은 줄당 단가를 쓴다.
 *
 * <p><b>DAST 횟수</b>: 웹사이트 점검 3회 = 5,000원. 토큰 지갑과 별도인 카운터
 * ({@code token_wallets.dast_*})로 관리한다 — 잔액이 부족해도 SAST·액션 토큰에는 영향이 없다.
 *
 * <p>플랜별 지급량과 한도는 {@link com.scanops.subscription.Plan}에 있다.
 */
public final class TokenPolicy {

    private TokenPolicy() {}

    // ── 토큰 환율(SAST·액션) ────────────────────────────────────────────────

    /** 충전 1구좌 가격(원). */
    public static final int PURCHASE_PRICE_KRW = 5_000;
    /** 충전 1구좌로 받는 토큰. */
    public static final long PURCHASE_TOKENS = 3_000;
    /** 무료체험 기간(일). PRO 전용. */
    public static final int TRIAL_DAYS = 7;
    /** 체험 종료 며칠 전에 알릴지. */
    public static final int TRIAL_NOTICE_DAYS_BEFORE = 1;
    /** 충전 토큰 유효기간(개월). */
    public static final int PURCHASED_TOKEN_VALID_MONTHS = 12;

    // ── DAST(웹) 스캔 — 횟수 단위 ───────────────────────────────────────────

    /** 가입 보너스 = 웹사이트 점검 1회(횟수, 토큰 아님). 계정당 평생 1회. */
    public static final int SIGNUP_BONUS_DAST_COUNT = 1;
    /** DAST 충전 단가: 5,000원 = 3회. */
    public static final int DAST_PURCHASE_PRICE_KRW = 5_000;
    public static final int DAST_PURCHASE_COUNT = 3;

    // ── 소스코드(SAST) 스캔 — 토큰 단위 ─────────────────────────────────────

    /** 소스 1,000줄당 단가. 1만 줄 = 3,000토큰 = 5,000원. */
    public static final long SAST_TOKENS_PER_1K_LINES = 300;
    /** 레포 스캔 최소 과금(= 1,000줄분). 소형 레포도 GPU 기동 비용은 동일. */
    public static final long SAST_MIN_TOKENS = 300;
    /** PR diff 스캔 최소 과금. 자동 트리거라 낮게 잡는다. */
    public static final long PR_SCAN_MIN_TOKENS = 100;
    /**
     * PR diff는 100줄 단위로 끊는다(단가는 소스코드와 동일: 100줄 = 30토큰 = 1,000줄당 300토큰).
     * 1,000줄 단위로 올림하면 20줄짜리 PR도 300토큰이 나가는데, PR 스캔은 푸시마다 자동으로
     * 도는 기능이라 그 단위가 너무 굵다.
     */
    public static final long PR_LINES_PER_UNIT = 100;
    public static final long PR_TOKENS_PER_UNIT = 30;

    /** 줄 수 추정용 — GitHub Trees API의 blob 크기(byte)를 줄 수로 환산할 때 쓰는 평균 바이트/줄. */
    public static final int ESTIMATED_BYTES_PER_LINE = 32;

    // ── 재스캔 / 부가 기능 ──────────────────────────────────────────────────

    /** 동일 타깃 재스캔이 무료로 처리되는 시간(시간). */
    public static final int RESCAN_FREE_WINDOW_HOURS = 24;
    /** 리포트 내 AI 상세설명·수정안 재생성 1건당. */
    public static final long AI_REGENERATE_TOKENS = 20;

    // ── 계산 헬퍼 ───────────────────────────────────────────────────────────

    /**
     * 분석된 줄 수에 대한 과금액. 첫 1,000줄은 고정 최소과금({@link #SAST_MIN_TOKENS}) —
     * 소형 레포도 GPU 기동 비용은 동일하기 때문. 그 이후부터는 1,000줄 단위 올림이 아니라
     * 정확히 {@link #SAST_TOKENS_PER_1K_LINES}/1,000줄 단가로 선형 과금한다.
     * (1,001줄에서 600토큰으로 튀는 "경계 절벽"을 없애기 위한 설계 — 990줄=300토큰,
     * 1,001줄=301토큰, 1,370줄=411토큰.)
     */
    public static long tokensForLines(long lines) {
        if (lines <= 0) return 0;
        if (lines <= 1_000) return SAST_MIN_TOKENS;
        long extraLines = lines - 1_000;
        long extraTokens = (extraLines * SAST_TOKENS_PER_1K_LINES + 999) / 1_000;
        return SAST_MIN_TOKENS + extraTokens;
    }

    /**
     * PR diff 과금 — 줄당 단가는 소스코드 점검과 같고(1,000줄=300토큰) 100줄 단위로 끊는다.
     * 최소 {@link #PR_SCAN_MIN_TOKENS}.
     */
    public static long tokensForPrLines(long lines) {
        if (lines <= 0) return 0;
        long units = (lines + PR_LINES_PER_UNIT - 1) / PR_LINES_PER_UNIT;
        return Math.max(PR_SCAN_MIN_TOKENS, units * PR_TOKENS_PER_UNIT);
    }

    /** blob 크기(byte) 합계 → 예상 과금액. 스캔 시작 시 예약(HOLD)할 금액 산정용. */
    public static long estimateTokensForBytes(long totalBytes) {
        return tokensForLines(totalBytes / ESTIMATED_BYTES_PER_LINE);
    }

    /** 토큰 → 원 환산(표시용). */
    public static long toKrw(long tokens) {
        return tokens * PURCHASE_PRICE_KRW / PURCHASE_TOKENS;
    }
}

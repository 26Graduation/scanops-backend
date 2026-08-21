package com.scanops.subscription;

/**
 * 구독 플랜과 플랜별 한도.
 *
 * <p>DAST(웹)와 SAST·GitHub App(액션)은 과금 단위가 다르다.
 * <ul>
 *   <li><b>SAST·액션</b>: 공용 토큰 지갑에서 줄 수만큼 차감(정률). {@link com.scanops.token.TokenPolicy}
 *       의 0.3 토큰/줄 단가로 스캔마다 실사용량을 정산한다.</li>
 *   <li><b>DAST(웹)</b>: 토큰이 아니라 <b>횟수</b>로 차감한다(정액, 1스캔=1회). 별도 카운터
 *       ({@code token_wallets.dast_*})를 같은 지갑 행에서 관리한다 — 토큰과 완전히 다른 통이다.</li>
 * </ul>
 *
 * <p>월 지급 기준(사업계획서 "6.1 구독 플랜"):
 * <ul>
 *   <li>PRO  19,900원 → 토큰 30,000(=SAST 10만 줄) / DAST 월 5회</li>
 *   <li>MAX  69,000원 → 토큰 120,000(=SAST 40만 줄) / DAST 월 30회</li>
 *   <li>TEAM 59,000원(기본 3인) → 토큰 100,000(=SAST 33만 줄) 공유 풀 / DAST 월 20회</li>
 *   <li>TEAM 추가 1인당 +20,000원 → 토큰 +69,000(액션 8만 줄+SAST 15만 줄 상당, 기존 사업계획서
 *       인당 증분에서 DAST 몫을 뺀 값) / DAST +7회</li>
 * </ul>
 *
 * <p>무료체험은 PRO 전용이며 월 지급량의 25%(토큰 7,500)만 준다. DAST도 비례해 1회만 준다
 * (5회의 25%=1.25 → 반내림). 7일 안에 한 달치를 소진하고 해지하는 어뷰징을 막기 위함.
 */
public enum Plan {

    //      priceKrw  tokens    trialTok  trialOk  extraTok  dastScans sastScans filesCap  dastLimit dastExtra trialDast
    FREE  ( 0,        0,        0,        false,   0,        1,        1,        50,       0,        0,        0),
    PRO   ( 19_900,   30_000,   7_500,    true,    0,        3,        2,        100,      5,        0,        1),
    MAX   ( 69_000,   120_000,  0,        false,   0,        3,        3,        400,      30,       0,        0),
    TEAM  ( 59_000,   100_000,  0,        false,   69_000,   5,        5,        400,      20,       7,        0);

    /** 월 요금(원). TEAM은 기본 3인 요금이고, 인원 추가 시 {@link #extraSeatPriceKrw}가 더해진다. */
    private final int priceKrw;
    /** 결제주기마다 지급되는 구독 토큰(SAST·액션 전용, DAST는 별도). */
    private final long monthlyTokens;
    /** 무료체험 기간에 지급되는 토큰 (체험 미지원 플랜은 0). */
    private final long trialTokens;
    /** 7일 무료체험 대상 플랜인지. PRO만 true. */
    private final boolean trialEligible;
    /** 기본 인원을 넘는 멤버 1명당 추가 지급 토큰량 (TEAM 전용, SAST·액션 몫만). */
    private final long extraSeatTokens;
    /** 동시에 실행할 수 있는 DAST(웹) 스캔 수. ZAP은 GPU와 무관한 별도 인스턴스에서 도는
     *  자원이라 SAST·PR과 한도를 분리한다. */
    private final int maxConcurrentDastScans;
    /** 동시에 실행할 수 있는 SAST·PR(GPU 모델) 스캔 수 — RunPod 서버리스 워커 풀 보호용. */
    private final int maxConcurrentSastScans;
    /** 레포 스캔 1회에 분석할 최대 파일 수. */
    private final int maxFilesPerScan;
    /** 결제주기마다 지급되는 DAST(웹) 점검 횟수. */
    private final int dastMonthlyLimit;
    /** 기본 인원을 넘는 멤버 1명당 추가 DAST 횟수 (TEAM 전용). */
    private final int dastExtraSeat;
    /** 무료체험 기간에 지급되는 DAST 횟수. */
    private final int trialDastCount;

    Plan(int priceKrw, long monthlyTokens, long trialTokens, boolean trialEligible,
         long extraSeatTokens, int maxConcurrentDastScans, int maxConcurrentSastScans, int maxFilesPerScan,
         int dastMonthlyLimit, int dastExtraSeat, int trialDastCount) {
        this.priceKrw = priceKrw;
        this.monthlyTokens = monthlyTokens;
        this.trialTokens = trialTokens;
        this.trialEligible = trialEligible;
        this.extraSeatTokens = extraSeatTokens;
        this.maxConcurrentDastScans = maxConcurrentDastScans;
        this.maxConcurrentSastScans = maxConcurrentSastScans;
        this.maxFilesPerScan = maxFilesPerScan;
        this.dastMonthlyLimit = dastMonthlyLimit;
        this.dastExtraSeat = dastExtraSeat;
        this.trialDastCount = trialDastCount;
    }

    public int priceKrw()               { return priceKrw; }
    public long monthlyTokens()         { return monthlyTokens; }
    public long trialTokens()           { return trialTokens; }
    public boolean trialEligible()      { return trialEligible; }
    public long extraSeatTokens()       { return extraSeatTokens; }
    public int maxConcurrentDastScans() { return maxConcurrentDastScans; }
    public int maxConcurrentSastScans() { return maxConcurrentSastScans; }
    public int maxFilesPerScan()        { return maxFilesPerScan; }
    public int dastMonthlyLimit()       { return dastMonthlyLimit; }
    public int dastExtraSeat()          { return dastExtraSeat; }
    public int trialDastCount()         { return trialDastCount; }

    /** 기본 인원(TEAM=3)을 넘는 멤버 1명당 추가 요금(원). TEAM 외 플랜은 0. */
    public int extraSeatPriceKrw() {
        return this == TEAM ? 20_000 : 0;
    }

    /** 멤버 수를 반영한 총 토큰 지급량(SAST·액션). TEAM 기본 3인, 그 외 플랜은 인원과 무관. */
    public long grantForSeats(int seats) {
        if (this != TEAM) return monthlyTokens;
        int extra = Math.max(0, seats - 3);
        return monthlyTokens + extraSeatTokens * extra;
    }

    /** 멤버 수를 반영한 DAST 월 한도. TEAM 기본 3인, 그 외 플랜은 인원과 무관. */
    public int dastLimitForSeats(int seats) {
        if (this != TEAM) return dastMonthlyLimit;
        int extra = Math.max(0, seats - 3);
        return dastMonthlyLimit + dastExtraSeat * extra;
    }

    /** 멤버 수를 반영한 월 청구액(원). TEAM 기본 3인, 그 외 플랜은 인원과 무관. */
    public int priceForSeats(int seats) {
        if (this != TEAM) return priceKrw;
        int extra = Math.max(0, seats - 3);
        return priceKrw + extraSeatPriceKrw() * extra;
    }
}

-- 스캔 과금(토큰) 기반 도입.
--
-- 환율: 5,000원 = 3,000 토큰
--   · 웹사이트 점검 1회   = 1,000 토큰  (3회 = 5,000원)
--   · 소스코드 1,000줄    =   300 토큰  (1만 줄 = 5,000원)
--
-- 지갑은 두 통으로 나눈다.
--   · subscription_balance : 결제주기마다 리셋, 이월 없음
--   · purchased_balance    : 충전분, 이월 O (유효기간 12개월)
-- 차감은 구독분 → 충전분 순서(사용자에게 유리).
--
-- 스캔은 비동기라 단순 차감이 불가능하다. SAST는 사전에 줄 수를 모르고,
-- 모델 서버 미연결 시 빈 결과로 완료되는 경로도 있어 다음 2단계로 처리한다.
--   HOLD(예약) → COMMIT(실사용 정산) 또는 RELEASE(전액 반환)

-- 1) 체험 자격은 users에 남긴다.
--    구독 행은 해지 후 재가입 시 새로 생기므로 subscriptions에 두면 체험이 부활한다.
ALTER TABLE users ADD COLUMN trial_used_at TIMESTAMP;

-- 2) 7일 무료체험(Pro 전용) + 간편 클릭 해지
ALTER TABLE subscriptions ADD COLUMN trial_end            TIMESTAMP;
ALTER TABLE subscriptions ADD COLUMN cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE;
-- plan/status는 VARCHAR라 TEAM/TRIALING 값 추가에 DDL 변경이 필요 없다.

-- 3) 토큰 지갑 (사용자당 1개)
CREATE TABLE token_wallets (
    wallet_id            UUID PRIMARY KEY,
    user_id              UUID NOT NULL UNIQUE REFERENCES users(user_id),
    subscription_balance BIGINT NOT NULL DEFAULT 0,   -- 주기 리셋분
    purchased_balance    BIGINT NOT NULL DEFAULT 0,   -- 충전분(이월)
    held_balance         BIGINT NOT NULL DEFAULT 0,   -- 진행 중 스캔이 잡아둔 예약분
    signup_bonus_granted BOOLEAN NOT NULL DEFAULT FALSE, -- 가입 보너스 1,000토큰: 계정당 평생 1회
    period_end           TIMESTAMP,                   -- 구독분 만료 시점
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL
);

-- 4) 예약(HOLD). 어느 통에서 얼마를 잡았는지 기억해야 정산/반환이 정확해진다.
CREATE TABLE token_holds (
    hold_id           UUID PRIMARY KEY,
    wallet_id         UUID NOT NULL REFERENCES token_wallets(wallet_id),
    scan_id           UUID REFERENCES scans(scan_id),
    reference_key     VARCHAR(255) NOT NULL UNIQUE,  -- 스캔당 1건 보장(웹훅 재전송 이중 차감 방지)
    held_amount       BIGINT NOT NULL,
    from_subscription BIGINT NOT NULL,
    from_purchased    BIGINT NOT NULL,
    committed_amount  BIGINT,                        -- 정산 확정액(실사용)
    status            VARCHAR(20) NOT NULL,          -- HELD / COMMITTED / RELEASED
    created_at        TIMESTAMP NOT NULL,
    settled_at        TIMESTAMP
);

CREATE INDEX idx_token_holds_wallet ON token_holds(wallet_id);
CREATE INDEX idx_token_holds_status ON token_holds(status);

-- 5) 원장 (append-only). 잔액은 지갑이 진실이고, 원장은 변경 이력이다.
CREATE TABLE token_ledger (
    entry_id                   UUID PRIMARY KEY,
    wallet_id                  UUID NOT NULL REFERENCES token_wallets(wallet_id),
    scan_id                    UUID REFERENCES scans(scan_id),
    entry_type                 VARCHAR(30) NOT NULL,
    -- GRANT / SIGNUP_BONUS / TRIAL_GRANT / PURCHASE / HOLD / COMMIT / RELEASE / EXPIRE / ADJUST
    amount                     BIGINT NOT NULL,      -- 지급 +, 소모 -
    subscription_balance_after BIGINT NOT NULL,
    purchased_balance_after    BIGINT NOT NULL,
    held_balance_after         BIGINT NOT NULL,
    memo                       TEXT,
    idempotency_key            VARCHAR(255) UNIQUE,
    created_at                 TIMESTAMP NOT NULL
);

CREATE INDEX idx_token_ledger_wallet ON token_ledger(wallet_id, created_at DESC);
CREATE INDEX idx_token_ledger_scan   ON token_ledger(scan_id);

-- 6) 기존 사용자에게 지갑 생성 + 가입 보너스(웹 점검 1회 = 1,000토큰) 소급 지급.
INSERT INTO token_wallets (wallet_id, user_id, subscription_balance, purchased_balance,
                           held_balance, signup_bonus_granted, created_at, updated_at)
SELECT gen_random_uuid(), u.user_id, 0, 1000, 0, TRUE, NOW(), NOW()
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM token_wallets w WHERE w.user_id = u.user_id);

INSERT INTO token_ledger (entry_id, wallet_id, scan_id, entry_type, amount,
                          subscription_balance_after, purchased_balance_after,
                          held_balance_after, memo, idempotency_key, created_at)
SELECT gen_random_uuid(), w.wallet_id, NULL, 'SIGNUP_BONUS', 1000,
       0, 1000, 0, '가입 보너스(웹사이트 점검 1회) 소급 지급',
       'signup-bonus:' || w.user_id, NOW()
FROM token_wallets w;

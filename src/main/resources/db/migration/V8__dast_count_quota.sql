-- DAST(웹) 점검을 토큰 차감에서 횟수 차감으로 분리한다.
--
-- 지금까지는 DAST도 SAST·GitHub App(액션)과 같은 토큰 지갑에서 빠졌지만,
-- 사업계획서 기준 DAST는 "월 N회" 정액이라 토큰으로 환산하지 않고 별도 카운터로 관리하는
-- 편이 요금제와 1:1로 맞는다. 같은 지갑 행(token_wallets)에 컬럼만 추가한다 — 팀 공유 지갑도
-- 그대로 재사용된다(토큰과 동일하게 팀원 전체가 DAST 횟수도 공유).

ALTER TABLE token_wallets ADD COLUMN dast_subscription_remaining BIGINT NOT NULL DEFAULT 0;
ALTER TABLE token_wallets ADD COLUMN dast_purchased_remaining    BIGINT NOT NULL DEFAULT 0;
ALTER TABLE token_wallets ADD COLUMN dast_held                   BIGINT NOT NULL DEFAULT 0;

-- 예약/원장 테이블은 단위(토큰 vs DAST 횟수)를 구분해야 같은 amount 컬럼을 혼동 없이 재사용할 수 있다.
ALTER TABLE token_holds  ADD COLUMN unit VARCHAR(10) NOT NULL DEFAULT 'TOKEN';
ALTER TABLE token_ledger ADD COLUMN unit VARCHAR(10) NOT NULL DEFAULT 'TOKEN';

-- 원장은 매 기록마다 "그 시점의 전체 잔액 스냅샷"을 남긴다(토큰 3개 + DAST 3개, unit과 무관하게 항상 둘 다).
ALTER TABLE token_ledger ADD COLUMN dast_subscription_after BIGINT NOT NULL DEFAULT 0;
ALTER TABLE token_ledger ADD COLUMN dast_purchased_after    BIGINT NOT NULL DEFAULT 0;
ALTER TABLE token_ledger ADD COLUMN dast_held_after          BIGINT NOT NULL DEFAULT 0;

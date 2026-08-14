-- Team 플랜 공유 토큰 풀.
--
-- MVP 단순화: 사용자는 팀을 최대 1개 소유하고, 최대 1개 팀에만 소속된다
-- (owner_user_id UNIQUE, team_members.user_id UNIQUE). 여러 팀에 걸치는 멤버십은
-- 지원하지 않는다 — "userId → 소속 팀"을 항상 1건으로 단순 조회할 수 있어야
-- 스캔 시점의 과금 지갑 결정이 애매해지지 않는다.
--
-- 구독(subscriptions)은 스키마를 바꾸지 않는다. TEAM 플랜 구독은 여전히 팀장(owner)의
-- 개인 구독 행에 걸리고, 그 구독의 plan이 TEAM이면 팀 지갑을 쓰도록 애플리케이션에서 분기한다.

CREATE TABLE teams (
    team_id       UUID PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    owner_user_id UUID NOT NULL UNIQUE REFERENCES users(user_id),
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);

CREATE TABLE team_members (
    team_member_id UUID PRIMARY KEY,
    team_id        UUID NOT NULL REFERENCES teams(team_id),
    user_id        UUID NOT NULL UNIQUE REFERENCES users(user_id),
    role           VARCHAR(20) NOT NULL,   -- OWNER / ADMIN / MEMBER
    joined_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_team_members_team ON team_members(team_id);

-- 지갑 소유자를 사용자 또는 팀 중 하나로 확장.
-- 팀 지갑은 user_id가 NULL이고 team_id가 채워진다 — 멤버 전원이 이 한 지갑을 공유해서 쓴다.
ALTER TABLE token_wallets ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE token_wallets ADD COLUMN team_id UUID UNIQUE REFERENCES teams(team_id);
ALTER TABLE token_wallets ADD CONSTRAINT chk_wallet_owner CHECK (
    (user_id IS NOT NULL AND team_id IS NULL) OR (user_id IS NULL AND team_id IS NOT NULL)
);

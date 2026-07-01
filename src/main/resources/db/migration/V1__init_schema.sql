-- ScanOps 초기 스키마 (빈 RDS 기준). Flyway가 스키마 소유, Hibernate는 validate.

CREATE TABLE users (
    user_id       UUID PRIMARY KEY,
    name          VARCHAR(255),
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    github_id     VARCHAR(255),
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);

CREATE TABLE subscriptions (
    subscription_id      UUID PRIMARY KEY,
    user_id              UUID NOT NULL REFERENCES users(user_id),
    plan                 VARCHAR(20) NOT NULL,   -- FREE / PRO / MAX
    status               VARCHAR(20) NOT NULL,   -- ACTIVE / CANCELED / PAST_DUE
    current_period_start TIMESTAMP,
    current_period_end   TIMESTAMP,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL
);

CREATE TABLE usage_meters (
    usage_id        UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES subscriptions(subscription_id),
    dast_used       INTEGER NOT NULL DEFAULT 0,
    sast_used       INTEGER NOT NULL DEFAULT 0,
    loc_used        BIGINT  NOT NULL DEFAULT 0
);

CREATE TABLE scans (
    scan_id        UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES users(user_id),
    scan_category  VARCHAR(10) NOT NULL,   -- DAST / SAST
    target         TEXT NOT NULL,
    status         VARCHAR(20) NOT NULL,   -- PENDING/RUNNING/COMPLETED/FAILED
    verified       BOOLEAN NOT NULL DEFAULT FALSE,
    scan_mode      VARCHAR(20),
    vuln_high      INTEGER NOT NULL DEFAULT 0,
    vuln_medium    INTEGER NOT NULL DEFAULT 0,
    vuln_low       INTEGER NOT NULL DEFAULT 0,
    max_cvss_score DOUBLE PRECISION,
    started_at     TIMESTAMP,
    completed_at   TIMESTAMP,
    created_at     TIMESTAMP NOT NULL
);

CREATE TABLE vulnerabilities (
    vuln_id      UUID PRIMARY KEY,
    scan_id      UUID NOT NULL REFERENCES scans(scan_id),
    vuln_type    VARCHAR(255),
    severity     VARCHAR(20),            -- HIGH / MEDIUM / LOW / INFORMATIONAL
    cvss_score   DOUBLE PRECISION,
    cvss_vector  VARCHAR(255),
    url          TEXT,
    parameter    VARCHAR(255),
    cause        TEXT,                   -- AI 분석/상세 통합
    solution     TEXT
);

CREATE TABLE domain_verifications (
    id           UUID PRIMARY KEY,
    domain       VARCHAR(255) UNIQUE NOT NULL,
    verify_token VARCHAR(255),
    verified     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP NOT NULL
);

CREATE INDEX idx_scans_user ON scans(user_id);
CREATE INDEX idx_vulns_scan ON vulnerabilities(scan_id);
CREATE INDEX idx_subs_user  ON subscriptions(user_id);

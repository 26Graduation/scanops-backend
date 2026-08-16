-- vulnerabilities 테이블이 옛 Hibernate(ddl-auto) 구조(id/job_id + ai_analysis/risk_level/summary…)로
-- 남아 현재 엔티티(com.scanops.vulnerability.Vulnerability: vuln_id/scan_id 기반)와 어긋나
-- 스키마 검증(ddl-auto=validate)이 실패했다.
-- Flyway가 기존 DB 위에 baseline(v1)으로 붙어 V1 재설계 스키마가 실제로는 적용된 적이 없기 때문.
--
-- 레거시 행(job 기반, 새 scans FK와 호환 불가)은 비파괴적으로 옆으로 보관하고,
-- 엔티티에 맞는 새 vulnerabilities 테이블을 만든다.

-- 1) 드리프트된 기존 테이블을 레거시로 보관.
--    PK 인덱스명이 스키마 내에서 유일해야 하므로 제약/인덱스도 함께 rename (신규 테이블 PK 충돌 방지).
ALTER TABLE vulnerabilities RENAME TO vulnerabilities_legacy;
ALTER TABLE vulnerabilities_legacy RENAME CONSTRAINT vulnerabilities_pkey TO vulnerabilities_legacy_pkey;

-- 2) 현재 엔티티에 맞는 새 테이블 (V1 정의와 동일).
CREATE TABLE vulnerabilities (
    vuln_id      UUID PRIMARY KEY,
    scan_id      UUID NOT NULL REFERENCES scans(scan_id),
    vuln_type    VARCHAR(255),
    severity     VARCHAR(255),            -- HIGH / MEDIUM / LOW / INFORMATIONAL
    cvss_score   DOUBLE PRECISION,
    cvss_vector  VARCHAR(255),
    url          TEXT,
    parameter    VARCHAR(255),
    cause        TEXT,
    solution     TEXT
);

-- V1__init_schema.sql:71 이 같은 이름의 인덱스를 이미 만든다.
-- 그대로 두면 **빈 DB 에서도** V3 가 반드시 실패한다
-- ("ERROR: relation \"idx_vulns_scan\" already exists" → flywayInitializer
--  BeanCreationException → 컨테이너 재시작 루프. 2026-08-17 온프레미스 기동 실측).
-- 이미 적용된 환경도 깨지지 않도록 IF NOT EXISTS 로 멱등화한다.
CREATE INDEX IF NOT EXISTS idx_vulns_scan ON vulnerabilities(scan_id);

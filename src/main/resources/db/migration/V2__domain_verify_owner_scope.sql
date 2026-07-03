-- DAST 도메인 인증을 전역 → 사용자별로 재설계.
-- 기존 domain 단일 UNIQUE 제거, (owner_id, domain) 복합 UNIQUE.
-- 인증 레코드는 재생성 가능(소유자 정보 없음)하므로 기존 행은 비운다.

DELETE FROM domain_verifications;

ALTER TABLE domain_verifications DROP CONSTRAINT IF EXISTS domain_verifications_domain_key;

ALTER TABLE domain_verifications ADD COLUMN owner_id    VARCHAR(255) NOT NULL;
ALTER TABLE domain_verifications ADD COLUMN verified_at TIMESTAMP;

ALTER TABLE domain_verifications
    ADD CONSTRAINT uq_domain_verif_owner_domain UNIQUE (owner_id, domain);

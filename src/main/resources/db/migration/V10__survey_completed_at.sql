-- 베타테스터 설문 참여 여부 — 계정당 1회만 허용하기 위한 완료 시각.
ALTER TABLE users ADD COLUMN survey_completed_at TIMESTAMP;

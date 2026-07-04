-- 스캔 실패 사유를 사용자에게 보여주기 위한 컬럼.
-- (예: 프라이빗 레포인데 ScanOps App 미설치)
ALTER TABLE scans ADD COLUMN failure_reason TEXT;

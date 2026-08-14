-- 스캔 기록 삭제(ScanService.deleteScan)가 이번 토큰 원장 도입 이후로 전부 500 에러였다.
-- token_holds/token_ledger가 scan_id를 참조하는데 ON DELETE 정책이 없어(기본 NO ACTION)
-- FK 위반으로 막혔다 — vulnerabilities는 애플리케이션에서 먼저 지워서 안 걸렸지만,
-- 토큰 예약/원장은 그 경로를 안 탔다.
--
-- 원장(token_ledger)은 append-only 감사 기록이라 스캔이 지워져도 "돈이 오간 사실"은 남아야
-- 한다 — 그래서 CASCADE(같이 삭제)가 아니라 SET NULL(참조만 끊고 행은 보존)로 둔다.
-- token_holds도 동일 — 정산은 이미 끝나 지갑 잔액에 반영됐고, scan_id는 추적용 참조일 뿐이다.

ALTER TABLE token_holds DROP CONSTRAINT token_holds_scan_id_fkey;
ALTER TABLE token_holds ADD CONSTRAINT token_holds_scan_id_fkey
    FOREIGN KEY (scan_id) REFERENCES scans(scan_id) ON DELETE SET NULL;

ALTER TABLE token_ledger DROP CONSTRAINT token_ledger_scan_id_fkey;
ALTER TABLE token_ledger ADD CONSTRAINT token_ledger_scan_id_fkey
    FOREIGN KEY (scan_id) REFERENCES scans(scan_id) ON DELETE SET NULL;

-- 체험 종료 1일 전 알림은 매시 정각 배치가 훑는다. trial_end가 알림 창(24시간) 안에
-- 들어오는 동안 배치가 여러 번 도는데, 발송 여부를 기억해두지 않으면 한 구독당
-- 최대 24통이 나간다. 이 컬럼으로 발송을 정확히 1회로 못박는다.
ALTER TABLE subscriptions ADD COLUMN trial_notified_at TIMESTAMP;

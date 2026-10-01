-- V0.24 에서 NULL 허용으로 추가했으므로 기존 행은 값이 비어 있다.
-- 그 상태로 NOT NULL 로 바꾸면 "ERROR 1138 (22004): Invalid use of NULL value" 로 실패하므로 먼저 채운다.
UPDATE cat SET last_meal_at = CURRENT_TIMESTAMP WHERE last_meal_at IS NULL;

ALTER TABLE cat
    MODIFY COLUMN last_meal_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

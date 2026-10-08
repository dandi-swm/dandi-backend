-- V0.28__split_push_setting.sql

-- 알림 수신 설정을 서비스/마케팅으로 나눈다.
--
-- 분석 완료·식사 리마인더는 사용자가 요청한 기능을 수행하는 알림이라 기본 수신이고,
-- 리텐션 복귀 유도는 사용자가 요청하지 않은 재참여 유도라 광고성 정보로 볼 여지가 있어
-- 사전 동의(기본 거부)가 필요하다.
ALTER TABLE profile
    ADD COLUMN is_service_push_enabled   BOOLEAN   NOT NULL DEFAULT TRUE,
    ADD COLUMN is_marketing_push_enabled BOOLEAN   NOT NULL DEFAULT FALSE,
    -- 광고성 정보는 동의 시점을 남겨야 한다. 2년마다 수신 동의를 재확인해야 하고,
    -- 분쟁이 생기면 당시 동의를 입증해야 한다.
    --
    -- 수신을 거부해도 지우지 않는다. "거부했다"와 "동의한 적 없다"는 다른 상태이고,
    -- 거부 뒤에도 그 전의 발송이 동의에 근거했음을 보여야 한다.
    ADD COLUMN marketing_agreed_at       TIMESTAMP NULL;

UPDATE profile
SET is_service_push_enabled = is_push_enabled;

ALTER TABLE profile
    DROP COLUMN is_push_enabled;

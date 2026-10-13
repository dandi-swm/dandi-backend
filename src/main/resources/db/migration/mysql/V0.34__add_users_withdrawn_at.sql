-- V0.34__add_users_withdrawn_at.sql

-- 탈퇴 시각. NULL이면 활성 계정이다.
-- 탈퇴는 users 행을 지우지 않고 식별정보만 비우므로(즉시 익명화), 행 자체를 지우는 deleted_at과 구분한다.
-- email·provider_user_id가 NULL이 되면 uk_users_provider_email / uk_users_provider_user_id의 슬롯이
-- 풀려(MySQL 유니크 인덱스는 NULL을 서로 다른 값으로 본다) 같은 이메일로 바로 재가입할 수 있다.
ALTER TABLE users
    ADD COLUMN withdrawn_at TIMESTAMP NULL;

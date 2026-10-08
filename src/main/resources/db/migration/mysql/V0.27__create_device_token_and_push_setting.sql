-- V0.26__create_device_token_and_push_setting.sql

-- 멀티 로그인을 허용하지 않으므로 사용자당 한 행만 둔다.
--
-- token에도 UNIQUE를 걸어야 한다. 로그아웃 없이 앱을 지운 뒤 같은 기기에서 다른 계정이
-- 로그인하면 같은 토큰이 두 사용자에 묶이는데, 그러면 이전 사용자의 알림이 새 사용자의 기기로 간다.
-- DB가 그 상태를 아예 못 만들게 막는다.
CREATE TABLE device_token
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL UNIQUE,
    token      VARCHAR(512) NOT NULL UNIQUE,
    platform   VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_device_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- 알림 수신 여부. 토큰과 수명주기가 달라 profile에 둔다.
-- 토큰은 갱신·무효·로그아웃으로 지워지지만 사용자가 끈 설정은 남아야 한다.
ALTER TABLE profile
    ADD COLUMN is_push_enabled BOOLEAN NOT NULL DEFAULT TRUE;
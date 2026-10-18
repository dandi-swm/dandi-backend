-- V0.40__create_admins.sql

-- 어드민(운영자). 어드민 웹에 Google 계정으로 로그인하는 팀원 한 명이 한 행이다.
--
-- 앱 사용자(users)와 테이블을 나눈다. users에 role을 두면 탈퇴·익명화·OAuth 가입 흐름에 어드민이 섞이고,
-- 앱 토큰과 어드민 토큰의 경계도 흐려진다.
--
-- 비밀번호 컬럼은 두지 않는다. Google ID 토큰을 검증한 뒤 email이 이 테이블에 있는지로 로그인한다.
-- email은 소문자로 저장하고 비교한다.
--
-- 어드민을 내보낼 때는 행을 지우지 않고 disabled_at을 기록한다. 이후 감사 로그가 admin_id를 참조한다.
-- 행은 환경(dev/prod)마다 직접 INSERT한다. 팀원 이메일을 저장소에 남기지 않기 위해서다.
CREATE TABLE admins
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disabled_at TIMESTAMP    NULL,
    CONSTRAINT uk_admins_email UNIQUE (email)
);
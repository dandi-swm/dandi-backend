-- V0.38__rename_constraints.sql

-- 제약·인덱스 이름을 CLAUDE.md 케이스 규칙(fk_{테이블}_{참조 대상}, uk_{테이블}_{컬럼들})으로 통일한다.
-- V0.4(history → meal), V0.31(복수형)의 RENAME TABLE은 제약 이름을 바꾸지 않아 옛 이름이 남아 있었다.
-- 인라인 UNIQUE로 MySQL이 자동으로 붙인 이름(user_id, email, token)도 함께 바꾼다.
--
-- FK는 이름을 바로 바꿀 수 없어 지우고 같은 정의로 다시 만든다.
-- foreign_key_checks = 0이면 FK 추가가 INPLACE로 처리되어 테이블을 복사하지 않는다.
-- 이미 같은 FK가 걸려 있던 데이터라 기존 행 검사를 건너뛰어도 안전하다.
-- ALGORITHM = INPLACE, LOCK = NONE을 명시해 두면 INPLACE가 불가능할 때 COPY로 넘어가지 않고 실패한다.
-- 코드와 엔티티는 제약 이름을 참조하지 않으므로(ddl-auto: validate도 검사하지 않음) blue/green 전환 중에도 안전하다.
SET foreign_key_checks = 0;

ALTER TABLE meals
DROP FOREIGN KEY fk_history_user,
    DROP FOREIGN KEY fk_meal_icon,
    RENAME INDEX fk_history_user TO fk_meals_user,
    RENAME INDEX fk_meal_icon TO fk_meals_icon,
    RENAME INDEX uk_meal_image_key TO uk_meals_image_key,
    ADD CONSTRAINT fk_meals_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_meals_icon FOREIGN KEY (icon_id) REFERENCES icons (id),
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE cats
DROP FOREIGN KEY fk_cat_user,
    RENAME INDEX user_id TO uk_cats_user_id,
    ADD CONSTRAINT fk_cats_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE device_tokens
DROP FOREIGN KEY fk_device_token_user,
    RENAME INDEX user_id TO uk_device_tokens_user_id,
    RENAME INDEX token TO uk_device_tokens_token,
    ADD CONSTRAINT fk_device_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE profiles
DROP FOREIGN KEY fk_profile_user,
    RENAME INDEX user_id TO uk_profiles_user_id,
    ADD CONSTRAINT fk_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE refresh_tokens
DROP FOREIGN KEY fk_refresh_token_user,
    RENAME INDEX uk_refresh_token_user_id TO uk_refresh_tokens_user_id,
    ADD CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE codes
    RENAME INDEX email TO uk_codes_email,
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE coins
    RENAME INDEX user_id TO uk_coins_user_id,
    ALGORITHM = INPLACE, LOCK = NONE;

SET foreign_key_checks = 1;
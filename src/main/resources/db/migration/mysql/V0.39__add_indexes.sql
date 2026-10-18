-- V0.39__add_meals_period_index.sql

-- 사용자별 기간 조회(findMealsByUserIdAndPeriod, countMealsByUserIdAndPeriod, existsCompletedMealAfter)가
-- user_id 단일 인덱스로 사용자의 식사 전체를 읽고 있어 (user_id, meal_at) 복합 인덱스를 추가한다.
-- deleted_at은 넣지 않는다. count 쿼리는 deleted_at을 보지 않고, 소프트 삭제 행은 소수라 범위를 좁힌 뒤 걸러도 된다.
-- fk_meals_user는 새 인덱스가 FK를 대신 받치므로 지운다.
ALTER TABLE meals
    ADD INDEX idx_meals_user_id_meal_at (user_id, meal_at),
DROP INDEX fk_meals_user,
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE inquiries
    ADD CONSTRAINT uk_inquiries_image_key UNIQUE (image_key),
    ALGORITHM = INPLACE, LOCK = NONE;
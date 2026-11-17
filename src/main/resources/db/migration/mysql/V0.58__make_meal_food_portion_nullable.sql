-- V0.58__make_meal_food_portion_nullable.sql

-- 음식 줄과 스냅샷 줄의 인분을 비워 둘 수 있게 한다. NULL은 아직 구하지 않은 인분이다.
--
-- 인분은 인식할 때 정하지 않고 영양을 계산할 때 구한다. 그래서 인식한 뒤 영양을 계산하기 전까지는 음식 줄의 인분이 비어 있다.
-- 영양 계산은 비어 있는 인분만 채우고, 사용자가 고친 인분은 덮지 않는다.
-- 스냅샷 줄은 AI가 처음 인식한 결과라, 인식이 인분을 내지 않으면 넣을 값이 없다.
--
-- NOT NULL을 푸는 것이라 구버전은 지금처럼 늘 값을 넣으며 그대로 동작하고, 롤백해도 기동을 막지 않는다.
-- NULL은 인분을 비워 저장하는 코드가 배포된 뒤에야 들어간다.
--
-- NULL 허용 여부는 INSTANT로 바꿀 수 없어 테이블을 다시 만든다. 그동안에도 읽고 쓸 수 있다.
-- ALGORITHM = INPLACE, LOCK = NONE을 명시해 두면 그렇게 할 수 없을 때 테이블을 잠그거나 복사하지 않고 실패한다.
ALTER TABLE meal_foods
    MODIFY COLUMN portion DECIMAL(2, 1) NULL,
    ALGORITHM = INPLACE,
    LOCK = NONE;

ALTER TABLE meal_food_snapshots
    MODIFY COLUMN portion DECIMAL(2, 1) NULL,
    ALGORITHM = INPLACE,
    LOCK = NONE;

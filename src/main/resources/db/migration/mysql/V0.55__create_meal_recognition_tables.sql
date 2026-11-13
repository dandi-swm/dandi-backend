-- V0.55__create_meal_recognition_tables.sql

-- 식사 인식 v2(/api/v2/meals)의 음식 줄 테이블과 AI가 처음 낸 목록의 스냅샷 테이블을 만들고,
-- meals·meal_analysis_jobs에 v2를 구분하는 컬럼을 더한다.
--
-- v2는 사진에서 음식 줄(이름·인분)을 먼저 인식하고, 줄마다 1인분 영양을 구해 인분을 곱한 합을 식사 영양으로 쓴다.
-- 사용자는 줄을 고치고 더하고 지울 수 있고, 그때마다 영양을 다시 계산한다.

-- analysis_version은 어느 분석으로 만든 식사인지(V1·V2)다. NULL은 V1이다.
-- 기존 행과, v1 API·구버전이 만드는 행은 NULL로 남는다.
-- input_category는 인식이 판정한 입력 유형(실제 음식 사진·제품·영양성분표 등)이다. 다시 인식하면 바뀐다.
-- v1 식사와 아직 인식하지 않은 식사는 NULL이다.
--
-- ALGORITHM = INSTANT를 명시해 두면 INSTANT가 불가능할 때 테이블을 복사하지 않고 실패한다.
ALTER TABLE meals
    ADD COLUMN analysis_version VARCHAR(10) NULL,
    ADD COLUMN input_category   VARCHAR(30) NULL,
    ALGORITHM = INSTANT;

-- AI가 식사를 처음 인식한 결과(식사 단위). 식사당 하나이고, 한 번 쓰면 바꾸지 않는다.
-- 사용자가 고친 지금 값(meals.name, meal_foods)과 비교해 무엇을 고쳤는지 본다.
-- 실패한 식사를 재분석할 때만 지우고 다시 만든다.
--
-- name은 AI가 추천한 대표 이름이다.
-- recognition_category는 인식한 곳(SYNC: 식사 기록 요청 안, WORKER: Worker의 인식 작업)이다.
-- model·prompt_version·input_category와 함께, 사용자가 고친 비율을 모델·프롬프트·인식한 곳·입력 유형별로 나눠 보는 데 쓴다.
--
-- meal_id 유니크 인덱스가 fk_meal_snapshots_meal의 인덱스를 겸한다.
CREATE TABLE meal_snapshots
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    meal_id              BIGINT       NOT NULL,
    name                 VARCHAR(100) NOT NULL,
    recognition_category VARCHAR(20)  NOT NULL,
    model                VARCHAR(50)  NOT NULL,
    prompt_version       VARCHAR(20)  NOT NULL,
    input_category       VARCHAR(30)  NOT NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_meal_snapshots_meal_id UNIQUE (meal_id),
    CONSTRAINT fk_meal_snapshots_meal FOREIGN KEY (meal_id) REFERENCES meals (id) ON DELETE CASCADE
);

-- AI가 처음 인식한 음식 한 줄(줄 단위). 한 번 쓰면 바꾸지 않는다.
-- 첫 인식의 이름과 인분만 담고 영양은 담지 않는다.
-- display_order는 인식이 낸 순서이고 1이 대표 음식이다.
CREATE TABLE meal_food_snapshots
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    meal_snapshot_id BIGINT        NOT NULL,
    display_order    INT           NOT NULL,
    name             VARCHAR(30)   NOT NULL,
    portion          DECIMAL(2, 1) NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_meal_food_snapshots_meal_snapshot FOREIGN KEY (meal_snapshot_id) REFERENCES meal_snapshots (id) ON DELETE CASCADE
);

-- 식사 안의 음식 한 줄(지금 값). 사용자가 고치는 값은 여기에 둔다.
--
-- 인식 결과로 만든 줄은 meal_food_snapshot_id로 스냅샷 줄을 가리키고, 사용자가 더한 줄은 NULL이다.
-- 가리키는 줄이 없는 스냅샷 줄은 사용자가 지운 줄이다.
--
-- display_order는 목록 순서이고 1이 대표 음식이다. 대표 음식의 아이콘이 식사 아이콘(meals.icon_id)이 된다.
--
-- serving_*은 1인분 영양이다. 줄 영양은 저장하지 않고 1인분 × portion으로 계산한다.
-- 아직 구하지 않았거나 이름이 바뀌어 다시 구해야 하는 줄은 NULL이다.
-- 사용자가 줄 영양을 직접 고치면 고친 값 ÷ portion을 1인분으로 저장한다. 다시 곱했을 때 맞도록 소수 둘째 자리까지 둔다.
--
-- is_nutrition_fixed는 사용자가 영양을 직접 고친 줄이고, 이름을 바꿔도 영양을 다시 구하지 않는다.
-- is_icon_fixed는 사용자가 아이콘을 직접 고른 줄이고, 이름을 바꿔도 아이콘을 다시 구하지 않는다.
--
-- fk_meal_foods_meal_food_snapshot은 ON DELETE SET NULL이다. 식사 행을 지우면 음식 줄과 스냅샷이 둘 다 연쇄 삭제되는데,
-- 기본값(RESTRICT)이면 스냅샷 줄이 음식 줄보다 먼저 지워질 때 막힌다(MySQL 8.4.9에서 FK 이름 순서만 바꿔 확인).
-- 지금 FK 이름으로는 음식 줄이 먼저 지워져 기본값으로도 지워지지만, 그 순서에 기대지 않으려고 SET NULL로 둔다.
CREATE TABLE meal_foods
(
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    meal_id               BIGINT        NOT NULL,
    meal_food_snapshot_id BIGINT        NULL,
    display_order         INT           NOT NULL,
    name                  VARCHAR(30)   NOT NULL,
    portion               DECIMAL(2, 1) NOT NULL,
    serving_calory        DECIMAL(7, 2) NULL,
    serving_carbs         DECIMAL(7, 2) NULL,
    serving_protein       DECIMAL(7, 2) NULL,
    serving_fat           DECIMAL(7, 2) NULL,
    is_nutrition_fixed    BOOLEAN       NOT NULL DEFAULT FALSE,
    icon_id               BIGINT        NULL,
    is_icon_fixed         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_meal_foods_meal FOREIGN KEY (meal_id) REFERENCES meals (id) ON DELETE CASCADE,
    CONSTRAINT fk_meal_foods_meal_food_snapshot FOREIGN KEY (meal_food_snapshot_id) REFERENCES meal_food_snapshots (id) ON DELETE SET NULL,
    CONSTRAINT fk_meal_foods_icon FOREIGN KEY (icon_id) REFERENCES icons (id)
);

-- 작업 종류(RECOGNITION: 음식 줄 인식, NUTRITION: 영양 계산)다. NULL은 지금의 v1 분석 작업이다.
-- 기존 행과, v1 API·구버전이 만드는 행은 NULL로 남는다.
ALTER TABLE meal_analysis_jobs
    ADD COLUMN category VARCHAR(20) NULL,
    ALGORITHM = INSTANT;

-- 테이블 추가와 nullable 컬럼 추가라 구버전은 이 테이블·컬럼을 모른 채 그대로 동작하고, 롤백해도 남은 것이 기동을 막지 않는다.
-- meals.status는 VARCHAR(20)이라 새 값(RECOGNIZING)을 위한 스키마 변경은 없다.

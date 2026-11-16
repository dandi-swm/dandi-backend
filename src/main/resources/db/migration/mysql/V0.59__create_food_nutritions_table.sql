-- V0.59__create_food_nutritions_table.sql

-- 음식 이름별 1인분 영양을 저장하는 테이블을 만든다.
--
-- LLM이 구한 1인분 영양과 아이콘을 음식 이름으로 저장해 두고, 같은 이름을 다시 구할 때 LLM을 부르지 않고 꺼내 쓴다.
-- 사진 없이 이름만으로 영양을 구하는 음식 줄(모형, 글씨, 계산이 끝난 식사에 사용자가 더한 줄)이 여기서 먼저 찾고,
-- 없으면 LLM에 물어 그 값을 여기에 채운다.
--
-- 값은 두 곳에서 들어온다. 이름만으로 LLM에 물은 값과, 음식 사진을 함께 보내 물은 값이다.
-- 사진에 적혀 있어 그대로 쓴 값은 넣지 않는다. 그 사람이 먹은 양의 값이라 다른 식사에 다시 쓸 수 없다.
--
-- name은 음식 이름이고 길이는 meal_foods.name과 같다. 이름 하나에 행 하나다.
-- serving_*은 1인분 영양이고 자릿수는 meal_foods.serving_*과 같다. 인분을 곱하기 전의 값만 둔다.
-- icon_id는 그 음식의 아이콘이다. 1인분 영양을 구하는 LLM이 함께 고른 것이라, 여기서 찾은 음식 줄은 LLM 없이 아이콘까지 받는다.
--
-- 테이블 추가라 구버전은 이 테이블을 모른 채 그대로 동작하고, 롤백해도 남은 테이블이 기동을 막지 않는다.
CREATE TABLE food_nutritions
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(30)   NOT NULL,
    serving_calory  DECIMAL(7, 2) NOT NULL,
    serving_carbs   DECIMAL(7, 2) NOT NULL,
    serving_protein DECIMAL(7, 2) NOT NULL,
    serving_fat     DECIMAL(7, 2) NOT NULL,
    icon_id         BIGINT        NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_food_nutritions_name UNIQUE (name),
    CONSTRAINT fk_food_nutritions_icon FOREIGN KEY (icon_id) REFERENCES icons (id)
);

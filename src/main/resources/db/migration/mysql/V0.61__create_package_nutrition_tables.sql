-- V0.61__create_package_nutrition_tables.sql

-- 제품 이름·용량별 영양을 저장하는 테이블과, 그 값이 사진에 적힌 값과 달랐던 기록을 쌓는 테이블을 만든다.
--
-- 제품(포장 식품)은 사진의 영양성분표에서 읽은 값이 가장 믿을 만하다. 그 값을 제품 이름과 용량으로 저장해 두고,
-- 같은 제품을 숫자가 보이지 않게 찍었을 때 모델에 묻지 않고 꺼내 쓴다.
-- 음식 이름별 1인분 영양(food_nutritions)과 따로 둔다. 그쪽은 모델이 이름만으로 구한 값이고 유효 기간이 있지만,
-- 이쪽은 사진에서 읽은 값이고 유효 기간이 없다.

-- package_nutritions: 제품 이름·용량별 영양.
--
-- name은 제품 이름이고 길이는 meal_foods.name과 같다. net_content와 net_content_unit은 총 내용량과 단위(G, ML)이고
-- meal_foods의 같은 컬럼과 뜻이 같다. 용량을 읽지 못한 제품은 둘 다 NULL이고, 용량이 있는 행과 서로 다른 행이다.
-- serving_*은 그 제품 하나의 값이다. 음식 줄의 1인분(meal_foods.serving_*)으로 그대로 들어가므로 이름과 자릿수를 같게 둔다.
-- icon_id는 그 제품의 아이콘이다. 사진에는 아이콘이 없어, 처음 저장할 때 모델이 고른 것을 함께 둔다.
--
-- is_nutrition_hint_complete는 serving_* 네 값이 모두 사진에서 읽은 값인지다.
-- 사진에 일부만 적혀 있던 제품은 나머지를 모델 값으로 채워 저장하는데, 그런 행은 FALSE다.
-- FALSE인 행은 나중에 네 값이 다 적힌 사진이 오면 그 값으로 바꾼다.
--
-- 이름과 용량이 같은 행은 하나다. MySQL은 NULL이 낀 유니크를 중복으로 보지 않아서, 컬럼을 그대로 묶으면
-- 용량을 읽지 못한 행이 같은 이름으로 여러 개 들어갈 수 있다. 그래서 유니크 제약에서는 NULL을 0과 빈 글자로 바꿔 비교한다.
-- 컬럼 값은 NULL 그대로라, "용량을 모름"은 meal_foods와 같게 NULL이다.
--
-- 테이블 추가라 구버전은 이 테이블을 모른 채 그대로 동작하고, 롤백해도 남은 테이블이 기동을 막지 않는다.
CREATE TABLE package_nutritions
(
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                       VARCHAR(30)   NOT NULL,
    net_content                DECIMAL(7, 1) NULL,
    net_content_unit           VARCHAR(10)   NULL,
    serving_calory             DECIMAL(7, 2) NOT NULL,
    serving_carbs              DECIMAL(7, 2) NOT NULL,
    serving_protein            DECIMAL(7, 2) NOT NULL,
    serving_fat                DECIMAL(7, 2) NOT NULL,
    icon_id                    BIGINT        NOT NULL,
    is_nutrition_hint_complete BOOLEAN       NOT NULL,
    created_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_package_nutritions_name_net_content_net_content_unit
        UNIQUE (name, (IFNULL(net_content, 0)), (IFNULL(net_content_unit, ''))),
    CONSTRAINT fk_package_nutritions_icon FOREIGN KEY (icon_id) REFERENCES icons (id)
);

-- package_nutrition_mismatches: 사진에 적힌 값이 다른 값과 달랐던 기록. 쌓기만 하고 고치지 않는다.
--
-- category는 무엇과 달랐는지다.
--   SAVED: 저장해 둔 값(package_nutritions)과 달랐다. 네 값이 다 적힌 사진이면 저장해 둔 행을 사진 값으로 덮어쓰므로,
--          이 기록의 serving_*이 덮어쓰기 전의 값이다.
--   NAMED: 저장해 둔 값이 없어 이름만으로 모델에 물었는데, 그 값이 사진 값과 달랐다.
-- 허용 오차를 조정하거나, 잘못 덮어쓴 값을 되돌릴 때 근거로 쓴다.
--
-- name, net_content, net_content_unit은 그때의 제품 이름과 용량이다.
-- nutrition_hint_*는 사진에 적혀 있던 값이다. 일부만 적혀 있던 경우가 있어 NULL을 허용한다.
-- serving_*은 비교한 쪽의 값(저장해 둔 값이나 모델 값)이다.
--
-- meal_id는 어느 식사에서 생긴 기록인지다. 식사가 지워져도 제품에 대한 기록은 남아야 해서 외래 키를 걸지 않는다.
-- 음식 줄(meal_foods)의 id를 두지 않는 것은, 음식 줄은 사용자가 목록을 고칠 때 행이 지워지기 때문이다.
CREATE TABLE package_nutrition_mismatches
(
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    category               VARCHAR(20)   NOT NULL,
    name                   VARCHAR(30)   NOT NULL,
    net_content            DECIMAL(7, 1) NULL,
    net_content_unit       VARCHAR(10)   NULL,
    nutrition_hint_calory  DECIMAL(7, 2) NULL,
    nutrition_hint_carbs   DECIMAL(7, 2) NULL,
    nutrition_hint_protein DECIMAL(7, 2) NULL,
    nutrition_hint_fat     DECIMAL(7, 2) NULL,
    serving_calory         DECIMAL(7, 2) NOT NULL,
    serving_carbs          DECIMAL(7, 2) NOT NULL,
    serving_protein        DECIMAL(7, 2) NOT NULL,
    serving_fat            DECIMAL(7, 2) NOT NULL,
    meal_id                BIGINT        NOT NULL,
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

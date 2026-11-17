-- V0.60__add_meal_food_net_content.sql

-- 음식 줄에 제품의 총 내용량(용량)을 둔다. 인식이 사진에서 읽은 값이고, 제품이 아니거나 읽지 못했으면 NULL이다.
--
-- 같은 제품도 용량에 따라 영양이 크게 다르다(190ml와 500ml). 제품 이름·용량별로 저장한 영양(package_nutritions)을
-- Worker가 찾을 때 이름과 함께 이 값을 쓴다.
--
-- net_content는 숫자, net_content_unit은 단위(G, ML)다. kg과 L는 g과 ml로 바꿔 저장한다.
-- 글자 하나("500ml")로 두지 않는 것은 표기가 달라(500ml, 0.5L) 같은 용량이 다른 값으로 저장되는 것을 막기 위해서다.
-- 둘은 함께 채우거나 함께 비운다.
--
-- nullable 컬럼 추가라 구버전은 이 컬럼을 모른 채 그대로 동작하고, 롤백해도 남은 컬럼이 기동을 막지 않는다.
-- ALGORITHM = INSTANT를 명시해 두면 INSTANT가 불가능할 때 테이블을 복사하지 않고 실패한다.
ALTER TABLE meal_foods
    ADD COLUMN net_content      DECIMAL(7, 1) NULL,
    ADD COLUMN net_content_unit VARCHAR(10)   NULL,
    ALGORITHM = INSTANT;

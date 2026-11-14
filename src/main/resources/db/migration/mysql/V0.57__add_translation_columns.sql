-- V0.57__add_translation_columns.sql

-- 운영자가 입력하는 상품·아이템·배너 문구의 번역. 앱 언어가 원본 언어(KO)가 아니면 이 값으로 응답한다.
--
-- 원본(한국어)은 지금 컬럼(name·title·description)에 그대로 두고, 번역만 언어 코드를 키로 담는다.
--   예: {"ja": "肉球クッション", "en": "Paw Cushion"}
-- 키는 Language.code(ko·ja·en)이고 ko는 넣지 않는다. 언어가 늘어도 컬럼을 추가하지 않으려고 JSON 하나로 둔다.
--
-- 기존 행과, 이 배포 중에 구버전이 만드는 행은 NULL이다. 번역이 없는 언어(NULL 포함)는 원본 컬럼 값으로 응답한다.
--
-- nullable 컬럼 추가라 구버전은 이 컬럼을 모른 채 그대로 동작하고, 롤백해도 남은 컬럼이 기동을 막지 않는다.
-- ALGORITHM = INSTANT를 명시해 두면 INSTANT가 불가능할 때 테이블을 복사하지 않고 실패한다.
ALTER TABLE products
    ADD COLUMN name_translations JSON NULL,
    ALGORITHM = INSTANT;

ALTER TABLE items
    ADD COLUMN name_translations JSON NULL,
    ALGORITHM = INSTANT;

ALTER TABLE shop_banners
    ADD COLUMN title_translations JSON NULL,
    ADD COLUMN description_translations JSON NULL,
    ALGORITHM = INSTANT;

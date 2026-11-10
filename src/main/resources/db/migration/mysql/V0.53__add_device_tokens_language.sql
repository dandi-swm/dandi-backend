-- V0.53__add_device_tokens_language.sql

-- 기기 앱이 지금 쓰는 언어. 기기 토큰을 등록할 때 X-Language 헤더로 갱신하고,
-- 요청 없이 보내는 푸시(식사 리마인더·리텐션)의 문구 언어를 정한다. 값은 Language enum 이름(KO·JA·EN)이다.
--
-- 기존 행은 채우지 않는다. NULL은 원본 언어(KO)로 보고, 앱이 다음에 기기 토큰을 등록할 때 채워진다.
--
-- nullable 컬럼 추가라 구버전은 이 컬럼을 모른 채 그대로 동작하고, 롤백해도 남은 컬럼이 기동을 막지 않는다.
-- ALGORITHM = INSTANT를 명시해 두면 INSTANT가 불가능할 때 테이블을 복사하지 않고 실패한다.
ALTER TABLE device_tokens
    ADD COLUMN language VARCHAR(10) NULL,
    ALGORITHM = INSTANT;

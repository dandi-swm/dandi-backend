-- V0.62__add_analysis_feedbacks_language.sql

-- 평가를 만든 요청의 앱 언어. 어드민이 분석 평가를 언어별로 나눠 보는 데 쓴다. 값은 Language enum 이름(KO·JA·EN)이다.
--
-- 기기 토큰의 언어는 지금 기기 언어라 나중에 바뀔 수 있어서, 평가할 때의 X-Language를 평가에 남긴다.
-- 기존 행은 V0.63에서 기기 토큰의 언어로 채운다.
--
-- nullable 컬럼 추가라 구버전은 이 컬럼을 모른 채 그대로 동작하고, 롤백해도 남은 컬럼이 기동을 막지 않는다.
-- ALGORITHM = INSTANT를 명시해 두면 INSTANT가 불가능할 때 테이블을 복사하지 않고 실패한다.
ALTER TABLE analysis_feedbacks
    ADD COLUMN language VARCHAR(10) NULL,
    ALGORITHM = INSTANT;

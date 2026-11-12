-- V0.54__add_job_language.sql

-- 작업을 만든 요청의 앱 언어. Worker는 요청 없이 돌기 때문에, 분석 결과(식사 이름·고양이 코멘트)와 리포트 문장,
-- 완료 푸시를 어느 언어로 쓸지 작업을 만들 때 받아 둔다. 값은 Language enum 이름(KO·JA·EN)이다.
--
-- 작업은 모두 사용자 요청 안에서 만들어진다(식사 기록·분석 재시도·리포트 구매). 그래서 기기 토큰이 아니라
-- 그 요청의 X-Language를 저장한다. 같은 요청의 결과를 같은 언어로 돌려주려는 것이다.
--
-- 기존 행과, 이 배포 중에 구버전이 만드는 행은 NULL이다. NULL은 원본 언어(KO)로 보고 지금처럼 한국어로 만든다.
--
-- nullable 컬럼 추가라 구버전은 이 컬럼을 모른 채 그대로 동작하고, 롤백해도 남은 컬럼이 기동을 막지 않는다.
-- ALGORITHM = INSTANT를 명시해 두면 INSTANT가 불가능할 때 테이블을 복사하지 않고 실패한다.
ALTER TABLE meal_analysis_jobs
    ADD COLUMN language VARCHAR(10) NULL,
    ALGORITHM = INSTANT;

ALTER TABLE report_generation_jobs
    ADD COLUMN language VARCHAR(10) NULL,
    ALGORITHM = INSTANT;

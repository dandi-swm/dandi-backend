-- V0.56__add_job_available_at.sql

-- 작업을 가져갈 수 있게 되는 시각이다. 만들 때 일정 시간 미룬 작업에만 채우고, 바로 가져갈 수 있는 작업은 NULL이다.
--
-- v2 영양 작업은 사용자가 인식된 음식 목록을 보고 고칠 시간을 주려고, 만든 뒤 일정 시간이 지나야 가져갈 수 있다.
-- 미루는 것 자체는 lease_expires_at을 미래로 두어 하는데, 그 값은 Worker가 가져갈 때 lease 만료 시각으로 덮인다.
-- 그래서 "언제부터 가져갈 수 있었는지"를 따로 남긴다.
--
-- 쓰는 곳은 지표 둘이다. 작업이 끝날 때까지 걸린 시간과 가장 오래 기다린 작업의 대기 시간을 created_at이 아니라
-- 이 시각부터 센다(NULL이면 created_at부터). 일부러 기다리게 한 시간이 처리가 밀린 시간으로 보이지 않게 하려는 것이다.
--
-- 기존 행과, v1 API·구버전이 만드는 행은 NULL로 남아 지금처럼 created_at부터 센다.
--
-- nullable 컬럼 추가라 구버전은 이 컬럼을 모른 채 그대로 동작하고, 롤백해도 남은 컬럼이 기동을 막지 않는다.
-- ALGORITHM = INSTANT를 명시해 두면 INSTANT가 불가능할 때 테이블을 복사하지 않고 실패한다.
ALTER TABLE meal_analysis_jobs
    ADD COLUMN available_at TIMESTAMP NULL,
    ALGORITHM = INSTANT;

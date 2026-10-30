-- V0.50__create_meal_analysis_jobs_table.sql

-- 영양 분석을 기다리는 식사를 Worker가 가져가도록 쌓아 두는 작업 테이블을 만든다.
--
-- 식사 하나에 작업 하나다. 끝난 작업(완료·최종 실패)은 행을 지우고, 사용자에게 보이는 분석 상태는 meals.status 하나다.
-- lease_expires_at이 NULL이면 대기, NOW()보다 이르면 lease가 만료된 작업이다. Worker는 둘을 lease_expires_at 순으로 가져간다.
-- InnoDB 인덱스에서 NULL이 가장 앞이라 대기 작업이 먼저 나오고, 대기끼리는 id 순이다. 만료된 작업은 대기 작업이 다 빠진 뒤에 나온다.
-- lease_count는 선점할 때마다 1씩 올린다. 몇 번째 시도인지이자, lease가 만료된 뒤 늦게 끝난 Worker가 결과를 쓰지 못하게 막는 비교값이다.
-- 시각 비교는 모두 DB 시계(NOW())로 한다.
--
-- meal_id 유니크 인덱스가 fk_meal_analysis_jobs_meal의 인덱스를 겸한다.
--
-- 테이블 추가라 구버전은 이 테이블을 모른 채 그대로 동작하고, 롤백해도 남은 테이블이 기동을 막지 않는다.
CREATE TABLE meal_analysis_jobs
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    meal_id          BIGINT    NOT NULL,
    lease_expires_at TIMESTAMP NULL,
    lease_count      INT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_meal_analysis_jobs_meal_id UNIQUE (meal_id),
    CONSTRAINT fk_meal_analysis_jobs_meal FOREIGN KEY (meal_id) REFERENCES meals (id) ON DELETE CASCADE,
    INDEX idx_meal_analysis_jobs_lease_expires_at (lease_expires_at)
);

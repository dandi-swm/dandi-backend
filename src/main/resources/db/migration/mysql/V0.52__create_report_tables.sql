-- V0.52__create_report_tables.sql

-- AI 주간 영양 리포트의 리포트·구매·생성 작업 테이블을 만든다.
--
-- 리포트(reports) 하나에 구매(report_purchases) 하나다. "다시 받기"는 같은 주를 새로 사는 것이라 리포트 행이 새로 생긴다.
-- 구매·시도·환불 기록은 추가만 하고 수정·삭제하지 않으므로 updated_at / deleted_at은 두지 않는다.
--
-- 탈퇴해도 users 행은 남으므로 리포트와 구매 기록도 남는다(상점 구매와 같다).
-- 사용자 행이 지워지면 함께 지워지도록 사용자에서 이어지는 FK는 모두 ON DELETE CASCADE다.

-- 리포트 한 건. 기간은 start_date(일요일)부터 7일이고, 끝일(토요일)은 저장하지 않고 계산한다.
-- 날짜는 Asia/Seoul 날짜다.
--
-- status는 WAITING → GENERATING → COMPLETED이고, 끝나지 않은 리포트는 CANCELED(사용자 취소)나 FAILED(생성 실패)로 끝난다.
-- 완료된 리포트는 취소·환불하지 않는다.
-- view_type은 앱이 리포트를 그릴 네이티브 뷰이고, content는 그 뷰가 정한 모양의 값이다.
-- 뷰를 바꾸면 새 view_type을 만들고, 이미 만든 리포트는 저장된 view_type·content로 그대로 그린다.
-- title·view_type·content·completed_at은 COMPLETED일 때만 채운다. content는 생성 시점 스냅샷이라 나중에 식사가 바뀌어도 그대로다.
-- read_at은 완료된 리포트를 처음 상세 조회한 시각이고, NULL이면 아직 안 본 리포트다.
--
-- 같은 주 중복 검사(실패·취소 제외)와 목록 조회가 user_id로 시작하므로 (user_id, start_date) 인덱스를 둔다.
-- 이 인덱스가 fk_reports_user의 인덱스를 겸한다.
CREATE TABLE reports
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    start_date   DATE         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    title        VARCHAR(100) NULL,
    view_type    VARCHAR(50)  NULL,
    content      JSON         NULL,
    read_at      TIMESTAMP    NULL,
    completed_at TIMESTAMP    NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_reports_user_id_start_date (user_id, start_date),
    CONSTRAINT fk_reports_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- 리포트 구매. price는 구매 시점 가격 스냅샷(0 또는 800)이고 0이면 첫 무료다.
-- 첫 무료 판정은 "price 0이고 리포트가 대기·생성 중·완료인 구매가 있는가"다.
-- 실패하거나 취소된 무료 구매는 세지 않으므로 무료 기회가 다시 생긴다.
--
-- 같은 Idempotency-Key로 다시 오면 처음 결과를 돌려준다. 구매 트랜잭션이 코인 행을 먼저 잠그므로
-- 같은 키 요청은 차례로 처리되고, 유니크 제약은 그 잠금이 빠졌을 때의 안전망이다.
-- (user_id, idempotency_key) 유니크 인덱스가 fk_report_purchases_user의 인덱스를 겸한다.
CREATE TABLE report_purchases
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    report_id       BIGINT      NOT NULL,
    price           INT         NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_report_purchases_user_id_idempotency_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_report_purchases_report_id UNIQUE (report_id),
    CONSTRAINT fk_report_purchases_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_purchases_report FOREIGN KEY (report_id) REFERENCES reports (id) ON DELETE CASCADE
);

-- 구매 시도. 성공·거부 모두 한 행이다.
-- 성공은 구매 트랜잭션 안에서, 거부는 구매 트랜잭션이 롤백된 뒤 별도 트랜잭션으로 저장한다.
--
-- requested_start_date·requested_price는 앱이 보낸 값을 그대로 저장한다(일요일이 아니거나 가격이 달라도 남긴다).
-- outcome은 결과(성공, 같은 키 재응답, 잘못된 주, 식사 부족, 같은 주, 가격 변경, 잔액 부족 등)다.
-- report_purchase_id는 성공했을 때만 채운다. app_version은 앱 버그와 조작된 요청을 구분하는 데 쓴다.
CREATE TABLE report_purchase_attempts
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id              BIGINT      NOT NULL,
    requested_start_date DATE        NOT NULL,
    requested_price      INT         NOT NULL,
    idempotency_key      VARCHAR(64) NOT NULL,
    outcome              VARCHAR(30) NOT NULL,
    report_purchase_id   BIGINT      NULL,
    app_version          VARCHAR(20) NULL,
    created_at           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_purchase_attempts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_purchase_attempts_report_purchase FOREIGN KEY (report_purchase_id) REFERENCES report_purchases (id) ON DELETE CASCADE
);

-- 환불. 유료 구매(price > 0)의 리포트가 취소되거나 생성에 실패하면 코인을 돌려준 기록이다.
-- 무료 구매는 돌려줄 코인이 없어 행이 없다(리포트 상태만 CANCELED·FAILED가 된다).
-- category는 왜 돌려줬는지(CANCELED: 사용자 취소, FAILED: 생성 실패)다.
-- 환불은 취소하지 않으므로 상태 컬럼이 없고, 행이 있으면 환불된 것이다(목록의 isRefunded).
-- 구매 하나는 한 번만 환불하므로 report_purchase_id가 유니크다. 취소와 실패가 겹쳐도 DB에서 두 번째 환불을 막는다.
-- coin_quantity는 돌려준 코인이다. 지금은 구매 price와 같다.
CREATE TABLE report_purchase_refunds
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_purchase_id BIGINT      NOT NULL,
    category           VARCHAR(20) NOT NULL,
    coin_quantity      INT         NOT NULL,
    created_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_report_purchase_refunds_report_purchase_id UNIQUE (report_purchase_id),
    CONSTRAINT fk_report_purchase_refunds_report_purchase FOREIGN KEY (report_purchase_id) REFERENCES report_purchases (id) ON DELETE CASCADE
);

-- 리포트 생성을 기다리는 작업. 리포트 Worker가 가져간다. 구조는 meal_analysis_jobs와 같다.
--
-- 리포트 하나에 작업 하나이고, 구매 트랜잭션에서 리포트와 함께 만든다.
-- 끝난 작업(완료·최종 실패)과 취소된 리포트의 작업은 행을 지운다.
-- lease_expires_at이 NULL이면 대기, NOW()보다 이르면 lease가 만료된 작업이다. Worker는 둘을 lease_expires_at 순으로 가져간다.
-- lease_count는 선점할 때마다 1씩 올린다. 몇 번째 시도인지이자, 늦게 끝난 Worker가 결과를 쓰지 못하게 막는 비교값이다.
-- 취소가 작업을 먼저 지우면 Worker의 완료 쓰기(lease_count 대조 삭제)가 0행이 되어 결과를 버린다.
-- 시각 비교는 모두 DB 시계(NOW())로 한다.
--
-- report_id 유니크 인덱스가 fk_report_generation_jobs_report의 인덱스를 겸한다.
CREATE TABLE report_generation_jobs
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id        BIGINT    NOT NULL,
    lease_expires_at TIMESTAMP NULL,
    lease_count      INT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_report_generation_jobs_report_id UNIQUE (report_id),
    CONSTRAINT fk_report_generation_jobs_report FOREIGN KEY (report_id) REFERENCES reports (id) ON DELETE CASCADE,
    INDEX idx_report_generation_jobs_lease_expires_at (lease_expires_at)
);

-- 테이블 추가라 구버전은 이 테이블을 모른 채 그대로 동작하고, 롤백해도 남은 테이블이 기동을 막지 않는다.
-- coin_histories.category는 VARCHAR(30)이라 새 값(REPORT_PURCHASE·REPORT_REFUND)을 위한 스키마 변경은 없다.

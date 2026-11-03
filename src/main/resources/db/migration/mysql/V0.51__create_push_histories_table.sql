-- V0.51__create_push_histories_table.sql

-- 언제 누구에게 어떤 푸시를 보냈는지 남기는 전송 내역 테이블을 만든다.
--
-- FCM에 보낸 시도마다 한 행이다. 일시 장애로 재시도하면 같은 notification_id의 행이 또 생기므로 유니크를 걸지 않는다.
-- outcome은 FCM이 접수한 결과까지만 뜻하고, 기기에 도착했는지는 알 수 없다.
-- platform은 기록 시점에 기기 토큰을 찾지 못하면 NULL이다.
--
-- idx_push_histories_user_id_sent_at이 fk_push_histories_user의 인덱스를 겸한다.
-- idx_push_histories_sent_at은 보관 기간이 지난 행을 지울 때 쓴다.
--
-- 테이블 추가라 구버전은 이 테이블을 모른 채 그대로 동작하고, 롤백해도 남은 테이블이 기동을 막지 않는다.
CREATE TABLE push_histories
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    notification_id VARCHAR(100) NOT NULL,
    category        VARCHAR(30)  NOT NULL,
    platform        VARCHAR(20)  NULL,
    title           VARCHAR(255) NOT NULL,
    body            VARCHAR(255) NOT NULL,
    outcome         VARCHAR(20)  NOT NULL,
    sent_at         TIMESTAMP    NOT NULL,
    CONSTRAINT fk_push_histories_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_push_histories_user_id_sent_at (user_id, sent_at),
    INDEX idx_push_histories_sent_at (sent_at)
);

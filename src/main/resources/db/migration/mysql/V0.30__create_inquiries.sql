-- V0.30__create_inquiries.sql

CREATE TABLE inquiries
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT        NOT NULL,
    category         VARCHAR(20)   NOT NULL,
    question_title   VARCHAR(100)  NOT NULL,
    question_content VARCHAR(2000) NOT NULL,
    image_key        VARCHAR(512)  NULL,
    app_version      VARCHAR(20)   NULL,
    answer_title     VARCHAR(100)  NULL,
    answer_content   VARCHAR(2000) NULL,
    answered_at      TIMESTAMP     NULL,
    answer_read_at   TIMESTAMP     NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       TIMESTAMP     NULL,
    INDEX idx_inquiries_user_id (user_id),
    CONSTRAINT fk_inquiries_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE meal_outbox (
                             id BIGINT AUTO_INCREMENT PRIMARY KEY,
                             meal_id BIGINT NOT NULL,
                             created_at TIMESTAMP(6) NOT NULL,
                             published_at TIMESTAMP(6) NULL,
                             INDEX idx_meal_outbox_pending (published_at, id)
);

CREATE TABLE meal_analysis_queue (
                                     id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                     outbox_id BIGINT NOT NULL,
                                     meal_id BIGINT NOT NULL,
                                     status VARCHAR(20) NOT NULL,
                                     created_at TIMESTAMP(6) NOT NULL,
                                     started_at TIMESTAMP(6) NULL,
                                     finished_at TIMESTAMP(6) NULL,
                                     CONSTRAINT uk_meal_analysis_queue_outbox UNIQUE (outbox_id),
                                     INDEX idx_meal_analysis_queue_ready (status, id)
);
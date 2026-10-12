-- V0.32__create_analysis_feedbacks.sql

-- AI 영양 분석 결과에 대한 사용자 평가(별점 + 이유).
-- 식사당 1건으로 고정하고 수정하지 않으므로 updated_at / deleted_at은 두지 않는다.
CREATE TABLE analysis_feedbacks
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    meal_id    BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    rating     FLOAT        NOT NULL,
    reason     VARCHAR(500) NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_analysis_feedbacks_meal_id UNIQUE (meal_id),
    CONSTRAINT fk_analysis_feedbacks_meal FOREIGN KEY (meal_id) REFERENCES meals (id) ON DELETE CASCADE,
    CONSTRAINT fk_analysis_feedbacks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
-- cat (V0.22에서 weight_updated_at이 추가되어 v0.20/30-cat.sql을 대체한다)
--
-- user당 1건 (user_id UNIQUE).
--
-- weight: CatWeight 단계값. -2(홀쭉냥) / -1(날씬냥) / 0(보통냥) / 1(통통냥) / 2(뚱냥이).
-- 이 범위를 벗어난 값을 넣으면 CatWeight.fromWeight()가 예외를 던져 체형 조회가 500이 된다.
--
-- weight_updated_at(V0.22): 마지막 체형 평가 시각. 여기서 3일(app.cat.weight-update-interval-days)이
-- 지나야 다음 평가가 일어난다. 체형 API를 호출했을 때 케이스별로 다른 결과가 나오도록 깔아 둔다.
--   cat 1: 주기 도래 + 구간 섭취량 부족          → -1 (통통냥 → 보통냥)
--   cat 2: 주기 도래 + 구간 과식(55-meal-cat-weight) → +1 (보통냥 → 통통냥)
--   cat 3: 주기 도래 + 기록 없음, 이미 하한      → 변화 없음 (하한 클램프 확인)
--   cat 4: 주기 미도래                           → 변화 없음 (평가 자체를 건너뜀)
--   cat 5: 주기 도래 + 기록 없음                 → -1 (날씬냥 → 홀쭉냥)

INSERT INTO cat (id, user_id, name, love, exp, weight, weight_updated_at, updated_at)
VALUES (1, 1, '냥이', 80, 1200, 1, TIMESTAMP(CURDATE() - INTERVAL 3 DAY, '00:00:00'), NOW()),
       (2, 2, '치즈', 45, 300, 0, TIMESTAMP(CURDATE() - INTERVAL 3 DAY, '00:00:00'), NOW()),
       (3, 3, '까망이', 100, 5400, -2, TIMESTAMP(CURDATE() - INTERVAL 5 DAY, '00:00:00'), NOW()),
       (4, 4, '두부', 10, 0, 2, TIMESTAMP(CURDATE(), '00:00:00'), NOW()),
       (5, 5, '삼색이', 62, 890, -1, TIMESTAMP(CURDATE() - INTERVAL 4 DAY, '00:00:00'), NOW());

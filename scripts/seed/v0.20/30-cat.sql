-- cat (기본 정의)
--
-- user당 1건 (user_id UNIQUE).
--
-- weight: CatWeight 단계값. -2(홀쭉냥) / -1(날씬냥) / 0(보통냥) / 1(통통냥) / 2(뚱냥이).
-- 이 범위를 벗어난 값을 넣으면 CatWeight.fromWeight()가 예외를 던져 체형 조회가 500이 된다.

INSERT INTO cat (id, user_id, name, love, exp, weight, updated_at)
VALUES (1, 1, '냥이', 80, 1200, 1, NOW()),
       (2, 2, '치즈', 45, 300, 0, NOW()),
       (3, 3, '까망이', 100, 5400, -2, NOW()),
       (4, 4, '두부', 10, 0, 2, NOW()),
       (5, 5, '삼색이', 62, 890, -1, NOW());

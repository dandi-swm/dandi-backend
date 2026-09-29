-- profile (user당 1건 — user_id UNIQUE)
--
-- gender: Gender enum 이름 문자열(MALE/FEMALE/OTHER). V0.14에서 TINYINT 0/1 인코딩을 대체했다.
-- 권장 섭취량 계산에 birth/gender/height/weight가 모두 쓰이므로 값을 다양하게 둔다.
-- user 5는 OTHER라 calculateRecommendedDailyIntake()가 DEFAULT_INTAKE로 빠진다 (fallback 확인용).

INSERT INTO profile (id, user_id, nickname, birth, gender, height, weight, coin, last_login_at, updated_at)
VALUES (1, 1, '개발자1', '1998-03-12', 'MALE', 178, 72, 1000, NOW(), NOW()),
       (2, 2, '개발자2', '2001-07-25', 'FEMALE', 162, 51, 250, NOW(), NOW()),
       (3, 3, '개발자3', '1995-11-02', 'MALE', 183, 88, 0, NOW(), NOW()),
       (4, 4, '개발자4', '2003-01-19', 'FEMALE', 158, 47, 4200, NOW(), NOW()),
       (5, 5, '개발자5', '1990-06-30', 'OTHER', 170, 65, 75, NOW(), NOW());

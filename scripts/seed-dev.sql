-- 로컬 개발용 더미 데이터 시드 스크립트
--
-- Flyway 에러로 DB를 날렸거나 볼륨을 초기화한 뒤, 테이블마다 5건씩 채워 넣는다.
-- 스키마는 Flyway가 만든 뒤여야 한다 (앱을 한 번 띄워 마이그레이션을 끝내고 실행할 것).
--
-- 실행: ./scripts/seed-dev.sh
--
-- 주의: 운영 DB에서 절대 실행하지 말 것. flyway_schema_history를 제외한
--       모든 테이블을 TRUNCATE 한 뒤 다시 채운다.

SET NAMES utf8mb4;

-- CURDATE()/NOW()를 KST 기준으로 계산한다.
-- 조회 API가 Asia/Seoul 경계로 날짜를 자르므로, 컨테이너 기본 시간대(UTC)를 쓰면
-- 오늘 만든 데이터가 어제 칸에 찍힐 수 있다.
SET time_zone = '+09:00';

-- TRUNCATE는 FK로 참조되는 테이블에 걸리므로 잠시 검사를 끈다.
-- AUTO_INCREMENT도 함께 초기화되어 아래 명시적 ID와 어긋나지 않는다.
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE meal;
TRUNCATE TABLE cat;
TRUNCATE TABLE profile;
TRUNCATE TABLE refresh_token;
TRUNCATE TABLE email_verification;
TRUNCATE TABLE icon;
TRUNCATE TABLE users;

SET FOREIGN_KEY_CHECKS = 1;


-- ---------------------------------------------------------------------------
-- users
--
-- password는 전부 'Password123!' 이다.
-- app.argon2 설정(salt 16 / hash 32 / parallelism 1 / memory 19456 / iterations 2)으로
-- 생성한 실제 Argon2id 해시라서 로그인 API가 그대로 통과한다.
-- 파라미터를 바꾸면 이 해시도 다시 만들어야 한다.
-- ---------------------------------------------------------------------------
INSERT INTO users (id, email, password, created_at)
VALUES (1, 'dev1@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', NOW()),
       (2, 'dev2@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', NOW()),
       (3, 'dev3@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', NOW()),
       (4, 'dev4@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', NOW()),
       (5, 'dev5@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', NOW());


-- ---------------------------------------------------------------------------
-- profile (user당 1건 — user_id UNIQUE)
--
-- gender: 0=남, 1=여
-- 권장 섭취량 계산에 birth/gender/height/weight가 모두 쓰이므로 값을 다양하게 둔다.
-- ---------------------------------------------------------------------------
INSERT INTO profile (id, user_id, nickname, birth, gender, height, weight, coin, last_login_at, updated_at)
VALUES (1, 1, '개발자1', '1998-03-12', 0, 178, 72, 1000, NOW(), NOW()),
       (2, 2, '개발자2', '2001-07-25', 1, 162, 51, 250, NOW(), NOW()),
       (3, 3, '개발자3', '1995-11-02', 0, 183, 88, 0, NOW(), NOW()),
       (4, 4, '개발자4', '2003-01-19', 1, 158, 47, 4200, NOW(), NOW()),
       (5, 5, '개발자5', '1990-06-30', 0, 170, 65, 75, NOW(), NOW());


-- ---------------------------------------------------------------------------
-- cat (user당 1건 — user_id UNIQUE)
-- ---------------------------------------------------------------------------
INSERT INTO cat (id, user_id, name, love, exp, weight, updated_at)
VALUES (1, 1, '냥이', 80, 1200, 3, NOW()),
       (2, 2, '치즈', 45, 300, 1, NOW()),
       (3, 3, '까망이', 100, 5400, 5, NOW()),
       (4, 4, '두부', 10, 0, 0, NOW()),
       (5, 5, '삼색이', 62, 890, 2, NOW());


-- ---------------------------------------------------------------------------
-- icon
-- ---------------------------------------------------------------------------
INSERT INTO icon (id, name, image_url)
VALUES (1, '샐러드', 'https://dummy.dandi.com/icons/salad.png'),
       (2, '김치찌개', 'https://dummy.dandi.com/icons/kimchi-stew.png'),
       (3, '샌드위치', 'https://dummy.dandi.com/icons/sandwich.png'),
       (4, '떡볶이', 'https://dummy.dandi.com/icons/tteokbokki.png'),
       (5, '밥', 'https://dummy.dandi.com/icons/rice.png');


-- ---------------------------------------------------------------------------
-- meal (전부 user 1 소유)
--
-- 일간/월간 조회를 바로 확인할 수 있도록 최근 며칠에 흩어 놓는다.
-- image_key는 실제 업로드 경로 규칙(meals/{userId}/{년}/{월}/{일}/{UUID}.{확장자})을 따르지만
-- S3에 실제 객체는 없다. 단건 조회 시 presigned URL은 발급되지만 열면 404다.
-- status가 COMPLETED가 아닌 건은 분석 전이므로 영양값이 NULL이다.
-- ---------------------------------------------------------------------------
INSERT INTO meal (id, user_id, name, carbs, protein, fat, score, calory, status, image_key, icon_id, meal_at,
                  created_at, updated_at, deleted_at)
VALUES (1, 1, '닭가슴살 샐러드', 18, 35, 12, 88, 320, 'COMPLETED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE(), '%Y/%c/%e'), '/11111111-1111-1111-1111-111111111111.jpg'),
        1, TIMESTAMP(CURDATE(), '08:30:00'), NOW(), NOW(), NULL),
       (2, 1, '떡볶이', 92, 11, 21, 42, 610, 'COMPLETED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE(), '%Y/%c/%e'), '/22222222-2222-2222-2222-222222222222.jpg'),
        4, TIMESTAMP(CURDATE(), '12:40:00'), NOW(), NOW(), NULL),
       (3, 1, '김치찌개와 공기밥', 78, 24, 18, 65, 540, 'COMPLETED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 1 DAY, '%Y/%c/%e'),
               '/33333333-3333-3333-3333-333333333333.jpg'),
        2, TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '19:10:00'), NOW(), NOW(), NULL),
       (4, 1, '분석 실패 케이스', NULL, NULL, NULL, NULL, NULL, 'FAILED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 3 DAY, '%Y/%c/%e'),
               '/44444444-4444-4444-4444-444444444444.jpg'),
        5, TIMESTAMP(CURDATE() - INTERVAL 3 DAY, '13:05:00'), NOW(), NOW(), NULL),
       (5, 1, '분석 대기 케이스', NULL, NULL, NULL, NULL, NULL, 'WAITING',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 5 DAY, '%Y/%c/%e'),
               '/55555555-5555-5555-5555-555555555555.jpg'),
        5, TIMESTAMP(CURDATE() - INTERVAL 5 DAY, '20:25:00'), NOW(), NOW(), NULL);


-- ---------------------------------------------------------------------------
-- refresh_token (user당 1건 — user_id UNIQUE)
--
-- 실제 서명된 JWT가 아니라 자리만 채우는 더미 문자열이다.
-- 재발급 API를 테스트하려면 로그인해서 진짜 토큰을 받아야 한다.
-- ---------------------------------------------------------------------------
INSERT INTO refresh_token (id, user_id, refresh_token, expires_at, created_at)
VALUES (1, 1, 'dummy-refresh-token-user-1', NOW() + INTERVAL 15 DAY, NOW()),
       (2, 2, 'dummy-refresh-token-user-2', NOW() + INTERVAL 15 DAY, NOW()),
       (3, 3, 'dummy-refresh-token-user-3', NOW() + INTERVAL 15 DAY, NOW()),
       (4, 4, 'dummy-refresh-token-user-4', NOW() + INTERVAL 15 DAY, NOW()),
       (5, 5, 'dummy-refresh-token-user-5', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 16 DAY);


-- ---------------------------------------------------------------------------
-- email_verification
--
-- 인증 완료 / 미완료 / 만료(app.mail.valid-time 5m 초과) 케이스를 섞어 둔다.
-- ---------------------------------------------------------------------------
INSERT INTO email_verification (id, email, code, is_verified, created_at)
VALUES (1, 'verify1@dandi.com', '111111', 1, NOW()),
       (2, 'verify2@dandi.com', '222222', 0, NOW()),
       (3, 'verify3@dandi.com', '333333', 0, NOW() - INTERVAL 10 MINUTE),
       (4, 'verify4@dandi.com', '444444', 1, NOW() - INTERVAL 1 DAY),
       (5, 'verify5@dandi.com', '555555', 0, NOW() - INTERVAL 7 DAY);


SELECT 'users' AS table_name, COUNT(*) AS rows_seeded
FROM users
UNION ALL
SELECT 'profile', COUNT(*)
FROM profile
UNION ALL
SELECT 'cat', COUNT(*)
FROM cat
UNION ALL
SELECT 'icon', COUNT(*)
FROM icon
UNION ALL
SELECT 'meal', COUNT(*)
FROM meal
UNION ALL
SELECT 'refresh_token', COUNT(*)
FROM refresh_token
UNION ALL
SELECT 'email_verification', COUNT(*)
FROM email_verification;

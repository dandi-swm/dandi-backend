-- 로컬 개발용 더미 데이터 시드 스크립트
--
-- Flyway 에러로 DB를 날렸거나 볼륨을 초기화한 뒤, 테이블마다 몇 건씩 채워 넣는다.
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
TRUNCATE TABLE code;
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
--
-- is_temp_password(V0.16): 비밀번호 찾기로 임시 비밀번호를 받은 상태인지.
-- user 4만 1로 두어 임시 비밀번호 분기를 밟아볼 수 있게 한다 (비밀번호 자체는 동일).
-- ---------------------------------------------------------------------------
INSERT INTO users (id, email, password, is_temp_password, created_at)
VALUES (1, 'dev1@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', 0,
        NOW()),
       (2, 'dev2@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', 0,
        NOW()),
       (3, 'dev3@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', 0,
        NOW()),
       (4, 'dev4@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', 1,
        NOW()),
       (5, 'dev5@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg', 0,
        NOW());


-- ---------------------------------------------------------------------------
-- profile (user당 1건 — user_id UNIQUE)
--
-- gender: Gender enum 이름 문자열(MALE/FEMALE/OTHER). V0.14에서 TINYINT 0/1 인코딩을 대체했다.
-- 권장 섭취량 계산에 birth/gender/height/weight가 모두 쓰이므로 값을 다양하게 둔다.
-- user 5는 OTHER라 calculateRecommendedDailyIntake()가 DEFAULT_INTAKE로 빠진다 (fallback 확인용).
-- ---------------------------------------------------------------------------
INSERT INTO profile (id, user_id, nickname, birth, gender, height, weight, coin, last_login_at, updated_at)
VALUES (1, 1, '개발자1', '1998-03-12', 'MALE', 178, 72, 1000, NOW(), NOW()),
       (2, 2, '개발자2', '2001-07-25', 'FEMALE', 162, 51, 250, NOW(), NOW()),
       (3, 3, '개발자3', '1995-11-02', 'MALE', 183, 88, 0, NOW(), NOW()),
       (4, 4, '개발자4', '2003-01-19', 'FEMALE', 158, 47, 4200, NOW(), NOW()),
       (5, 5, '개발자5', '1990-06-30', 'OTHER', 170, 65, 75, NOW(), NOW());


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
--
-- 여기만 더미가 아니라 실제 운영 데이터다. 아이콘은 추가만 되고 기존 행은 바뀌지 않는
-- append-only 테이블이라, DB를 새로 만들더라도 아래 행이 id까지 그대로 들어가야 한다.
-- meal.icon_id가 이 id를 그대로 가리키고, 앱이 내려주는 이미지 URL도 여기서 나온다.
--
-- 아래 12건은 2026-09-11 기준 실제 icon 테이블을 그대로 옮긴 것이다.
-- 운영에 아이콘을 추가하면 이 목록에도 같은 id/name/image_url로 추가할 것.
--
-- GeminiNutritionAnalysisClient는 IconService.getAllIcons()로 이 테이블을 읽어
-- 프롬프트에 아이콘 목록을 넣는다. 즉 여기 없는 id는 AI도 고를 수 없다.
-- 프롬프트가 "뚜렷하게 맞는 것이 없으면 5(밥)"를 fallback으로 지정하므로 id 5는 반드시 있어야 한다.
--
-- IconService는 @Cacheable("icons")로 Caffeine 캐시를 타므로,
-- 앱이 떠 있는 상태에서 이 스크립트를 돌렸다면 앱을 재시작해야 새 아이콘이 반영된다.
-- ---------------------------------------------------------------------------
INSERT INTO icon (id, name, image_url)
VALUES (1, '샐러드', 'https://cdn.nyummy.co.kr/icons/1.jpeg'),
       (2, '빵', 'https://cdn.nyummy.co.kr/icons/2.jpeg'),
       (3, '샌드위치', 'https://cdn.nyummy.co.kr/icons/3.jpeg'),
       (4, '떡볶이', 'https://cdn.nyummy.co.kr/icons/4.jpeg'),
       (5, '밥', 'https://cdn.nyummy.co.kr/icons/5.jpeg'),
       (6, '아이스아메리카노', 'https://cdn.nyummy.co.kr/icons/6.jpeg'),
       (7, '햄버거', 'https://cdn.nyummy.co.kr/icons/7.jpeg'),
       (8, '피자', 'https://cdn.nyummy.co.kr/icons/8.jpeg'),
       (9, '케이크', 'https://cdn.nyummy.co.kr/icons/9.jpeg'),
       (10, '치킨', 'https://cdn.nyummy.co.kr/icons/10.jpeg'),
       (11, '김밥', 'https://cdn.nyummy.co.kr/icons/11.jpeg'),
       (12, '스파게티', 'https://cdn.nyummy.co.kr/icons/12.jpeg');


-- ---------------------------------------------------------------------------
-- meal (id 1~7은 user 1, id 8은 user 2 소유)
--
-- 일간/월간 조회를 바로 확인할 수 있도록 최근 며칠에 흩어 놓는다.
-- image_key는 실제 업로드 경로 규칙(meals/{userId}/{년}/{월}/{일}/{UUID}.{확장자})을 따르지만
-- S3에 실제 객체는 없다. 단건 조회 시 presigned URL은 발급되지만 열면 404다.
-- image_key에는 UNIQUE 제약(uk_meal_image_key)이 걸려 있으므로 행을 늘릴 때 UUID를 겹치지 말 것.
-- status가 COMPLETED가 아닌 건은 분석 전이므로 영양값이 NULL이다.
-- MealStatus 전 케이스(WAITING/ANALYZING/COMPLETED/FAILED)를 하나씩 깔아 둔다.
-- 이 중 WAITING/FAILED만 ANALYZABLE_STATUSES라 재시도 API가 받아준다.
--
-- id 7은 deleted_at이 찍힌 소프트 삭제 건이라 조회 쿼리(deletedAt is null)에서 빠진다.
-- id 8은 user 2 소유라 user 1 토큰으로 단건 조회하면 MEAL_NOT_FOUND가 나온다 (validateOwnership 확인용).
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
        5, TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '19:10:00'), NOW(), NOW(), NULL),
       (4, 1, '분석 실패 케이스', NULL, NULL, NULL, NULL, NULL, 'FAILED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 3 DAY, '%Y/%c/%e'),
               '/44444444-4444-4444-4444-444444444444.jpg'),
        5, TIMESTAMP(CURDATE() - INTERVAL 3 DAY, '13:05:00'), NOW(), NOW(), NULL),
       (5, 1, '분석 대기 케이스', NULL, NULL, NULL, NULL, NULL, 'WAITING',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 5 DAY, '%Y/%c/%e'),
               '/55555555-5555-5555-5555-555555555555.jpg'),
        5, TIMESTAMP(CURDATE() - INTERVAL 5 DAY, '20:25:00'), NOW(), NOW(), NULL),
       (6, 1, '분석 진행 중 케이스', NULL, NULL, NULL, NULL, NULL, 'ANALYZING',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 5 DAY, '%Y/%c/%e'),
               '/66666666-6666-6666-6666-666666666666.jpg'),
        3, TIMESTAMP(CURDATE() - INTERVAL 5 DAY, '08:15:00'), NOW(), NOW(), NULL),
       (7, 1, '삭제된 케이스', 55, 20, 15, 70, 480, 'COMPLETED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 2 DAY, '%Y/%c/%e'),
               '/77777777-7777-7777-7777-777777777777.jpg'),
        2, TIMESTAMP(CURDATE() - INTERVAL 2 DAY, '12:00:00'), NOW(), NOW(), NOW()),
       (8, 2, '다른 유저의 식사', 40, 30, 9, 75, 400, 'COMPLETED',
        CONCAT('meals/2/', DATE_FORMAT(CURDATE(), '%Y/%c/%e'), '/88888888-8888-8888-8888-888888888888.jpg'),
        7, TIMESTAMP(CURDATE(), '13:20:00'), NOW(), NOW(), NULL);


-- ---------------------------------------------------------------------------
-- refresh_token (user당 1건 — user_id UNIQUE)
--
-- 실제 서명된 JWT가 아니라 자리만 채우는 더미 문자열이다.
-- 재발급 API를 테스트하려면 로그인해서 진짜 토큰을 받아야 한다.
--
-- expires_at은 V0.18에서 없어졌고, V0.17의 absolute_expires_at(재로그인 없이 유지되는 상한)이 대신 들어간다.
-- 값은 app.jwt.refresh-time-to-live(15d)를 기준으로 잡았다.
-- user 5만 이미 지난 시각이라 절대 만료된 세션 케이스가 된다.
-- ---------------------------------------------------------------------------
INSERT INTO refresh_token (id, user_id, refresh_token, absolute_expires_at, created_at)
VALUES (1, 1, 'dummy-refresh-token-user-1', NOW() + INTERVAL 15 DAY, NOW()),
       (2, 2, 'dummy-refresh-token-user-2', NOW() + INTERVAL 15 DAY, NOW()),
       (3, 3, 'dummy-refresh-token-user-3', NOW() + INTERVAL 15 DAY, NOW()),
       (4, 4, 'dummy-refresh-token-user-4', NOW() + INTERVAL 15 DAY, NOW()),
       (5, 5, 'dummy-refresh-token-user-5', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 16 DAY);


-- ---------------------------------------------------------------------------
-- code (이메일 인증 코드 — email UNIQUE)
--
-- CodeService 분기를 하나씩 밟아볼 수 있도록 케이스를 섞어 둔다.
--   - expires_at < NOW() : 만료된 코드. 재발송하면 코드와 만료 시각이 갱신된다
--   - send_count >= 5    : 발송 한도 도달. 재발송이 거부된다
-- 유효 기간은 app.jwt.email-challenge-time-to-live(5m)를 따른다.
-- ---------------------------------------------------------------------------
INSERT INTO code (id, email, code, attempt_count, send_count, expires_at)
VALUES (1, 'verify1@dandi.com', '111111', 0, 1, NOW() + INTERVAL 5 MINUTE),
       (2, 'verify2@dandi.com', '222222', 2, 3, NOW() + INTERVAL 5 MINUTE),
       (3, 'verify3@dandi.com', '333333', 0, 1, NOW() - INTERVAL 10 MINUTE),
       (4, 'verify4@dandi.com', '444444', 0, 5, NOW() + INTERVAL 5 MINUTE),
       (5, 'verify5@dandi.com', '555555', 4, 2, NOW() - INTERVAL 1 DAY);


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
SELECT 'code', COUNT(*)
FROM code;

-- meal (id 1~7은 user 1, id 8은 user 2 소유)
--
-- cat_comment(V0.20) 칼럼을 쓰므로 v0.20/ 아래에 둔다.
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
--
-- cat_comment(V0.20): 영양 분석과 함께 생성되는 고양이 말투 피드백.
-- 분석이 끝나지 않은 건(WAITING/ANALYZING/FAILED)은 영양값과 마찬가지로 NULL이다.

INSERT INTO meal (id, user_id, name, carbs, protein, fat, score, calory, cat_comment, status, image_key, icon_id,
                  meal_at, created_at, updated_at, deleted_at)
VALUES (1, 1, '닭가슴살 샐러드', 18, 35, 12, 88, 320,
        '단백질 챙긴 거 칭찬한다냥! 탄수화물이 적으니 다음 끼니엔 통곡물 빵 한 조각 곁들이면 완벽하다냥~', 'COMPLETED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE(), '%Y/%c/%e'), '/11111111-1111-1111-1111-111111111111.jpg'),
        1, TIMESTAMP(CURDATE(), '08:30:00'), NOW(), NOW(), NULL),
       (2, 1, '떡볶이', 92, 11, 21, 42, 610,
        '탄수화물이 폭발했다냥... 다음 끼니는 두부나 계란으로 단백질을 채워주면 좋겠다냥!', 'COMPLETED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE(), '%Y/%c/%e'), '/22222222-2222-2222-2222-222222222222.jpg'),
        4, TIMESTAMP(CURDATE(), '12:40:00'), NOW(), NOW(), NULL),
       (3, 1, '김치찌개와 공기밥', 78, 24, 18, 65, 540,
        '국물까지 다 먹었다냥? 나트륨이 걱정이다냥. 다음엔 나물 반찬을 곁들여보자냥~', 'COMPLETED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 1 DAY, '%Y/%c/%e'),
               '/33333333-3333-3333-3333-333333333333.jpg'),
        5, TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '19:10:00'), NOW(), NOW(), NULL),
       (4, 1, '분석 실패 케이스', NULL, NULL, NULL, NULL, NULL, NULL, 'FAILED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 3 DAY, '%Y/%c/%e'),
               '/44444444-4444-4444-4444-444444444444.jpg'),
        5, TIMESTAMP(CURDATE() - INTERVAL 3 DAY, '13:05:00'), NOW(), NOW(), NULL),
       (5, 1, '분석 대기 케이스', NULL, NULL, NULL, NULL, NULL, NULL, 'WAITING',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 5 DAY, '%Y/%c/%e'),
               '/55555555-5555-5555-5555-555555555555.jpg'),
        5, TIMESTAMP(CURDATE() - INTERVAL 5 DAY, '20:25:00'), NOW(), NOW(), NULL),
       (6, 1, '분석 진행 중 케이스', NULL, NULL, NULL, NULL, NULL, NULL, 'ANALYZING',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 5 DAY, '%Y/%c/%e'),
               '/66666666-6666-6666-6666-666666666666.jpg'),
        3, TIMESTAMP(CURDATE() - INTERVAL 5 DAY, '08:15:00'), NOW(), NOW(), NULL),
       (7, 1, '삭제된 케이스', 55, 20, 15, 70, 480,
        '맛있게 먹었다냥! 다음엔 채소도 곁들여보자냥~', 'COMPLETED',
        CONCAT('meals/1/', DATE_FORMAT(CURDATE() - INTERVAL 2 DAY, '%Y/%c/%e'),
               '/77777777-7777-7777-7777-777777777777.jpg'),
        2, TIMESTAMP(CURDATE() - INTERVAL 2 DAY, '12:00:00'), NOW(), NOW(), NOW()),
       (8, 2, '다른 유저의 식사', 40, 30, 9, 75, 400,
        '골고루 잘 먹었다냥! 채소만 조금 더 챙기면 완벽하다냥~', 'COMPLETED',
        CONCAT('meals/2/', DATE_FORMAT(CURDATE(), '%Y/%c/%e'), '/88888888-8888-8888-8888-888888888888.jpg'),
        7, TIMESTAMP(CURDATE(), '13:20:00'), NOW(), NOW(), NULL);

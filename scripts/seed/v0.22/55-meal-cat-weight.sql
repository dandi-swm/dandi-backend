-- 체형 증가(+1)를 확인하기 위한 추가 식사 (user 2 소유, id 9~10)
--
-- V0.22 오버레이에만 있으므로, 그 아래 버전 스키마에서는 실행되지 않는다.
--
-- cat 2의 평가 구간 [오늘-3일, 오늘)에 들어가야 하므로 오늘이 아닌 1~2일 전에 둔다.
-- 두 건 합계 6600kcal로, user 2의 목표(권장 1700 * 3일 = 5100)의 120%인 6120을 넘긴다.
-- profile 2(나이·키·몸무게)나 app.cat.weight-update-tolerance를 바꾸면 이 금액도 다시 맞춰야 한다.

INSERT INTO meal (id, user_id, name, carbs, protein, fat, score, calory, cat_comment, status, image_key, icon_id,
                  meal_at, created_at, updated_at, deleted_at)
VALUES (9, 2, '페퍼로니 피자 한 판', 400, 140, 140, 25, 3420,
        '한 판을 혼자 다 먹었다냥?! 내일은 나랑 같이 샐러드를 먹자냥...', 'COMPLETED',
        CONCAT('meals/2/', DATE_FORMAT(CURDATE() - INTERVAL 1 DAY, '%Y/%c/%e'),
               '/99999999-9999-9999-9999-999999999999.jpg'),
        8, TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '20:10:00'), NOW(), NOW(), NULL),
       (10, 2, '양념치킨과 맥주', 180, 210, 180, 20, 3180,
        '치맥은 행복하지만 지방이 너무 많다냥! 다음 끼니는 가볍게 두부 샐러드 어떠냥?', 'COMPLETED',
        CONCAT('meals/2/', DATE_FORMAT(CURDATE() - INTERVAL 2 DAY, '%Y/%c/%e'),
               '/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg'),
        10, TIMESTAMP(CURDATE() - INTERVAL 2 DAY, '21:30:00'), NOW(), NOW(), NULL);

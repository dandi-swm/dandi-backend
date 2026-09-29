-- refresh_token (user당 1건 — user_id UNIQUE)
--
-- 실제 서명된 JWT가 아니라 자리만 채우는 더미 문자열이다.
-- 재발급 API를 테스트하려면 로그인해서 진짜 토큰을 받아야 한다.
--
-- expires_at은 V0.18에서 없어졌고, V0.17의 absolute_expires_at(재로그인 없이 유지되는 상한)이 대신 들어간다.
-- 재발급(rotate)은 이 값을 건드리지 않고, 로그인/회원가입(restart)만 90일 뒤로 새로 찍는다.
-- 값은 app.jwt.refresh-absolute-time-to-live(90d)를 따른다. 설정을 바꾸면 아래 INTERVAL도 같이 맞출 것.
-- user 5만 이미 지난 시각이라, /refresh가 INVALID_REFRESH_TOKEN으로 막히는 절대 만료 케이스가 된다.

INSERT INTO refresh_token (id, user_id, refresh_token, absolute_expires_at, created_at)
VALUES (1, 1, 'dummy-refresh-token-user-1', NOW() + INTERVAL 90 DAY, NOW()),
       (2, 2, 'dummy-refresh-token-user-2', NOW() + INTERVAL 90 DAY, NOW()),
       (3, 3, 'dummy-refresh-token-user-3', NOW() + INTERVAL 90 DAY, NOW()),
       (4, 4, 'dummy-refresh-token-user-4', NOW() + INTERVAL 90 DAY, NOW()),
       (5, 5, 'dummy-refresh-token-user-5', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 91 DAY);

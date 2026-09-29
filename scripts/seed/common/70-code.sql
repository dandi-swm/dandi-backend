-- code (이메일 인증 코드 — email UNIQUE)
--
-- CodeService 분기를 하나씩 밟아볼 수 있도록 케이스를 섞어 둔다.
--   - expires_at < NOW() : 만료된 코드. 재발송하면 코드와 만료 시각이 갱신된다
--   - send_count >= 5    : 발송 한도 도달. 재발송이 거부된다
-- 유효 기간은 app.jwt.email-challenge-time-to-live(5m)를 따른다.
--
-- type(V0.19): 발급 목적(AuthPurpose 이름 문자열).
--   - SIGNUP은 미가입 이메일에만 발급되므로 verify1~5(미가입)가 전부 SIGNUP
--   - RESET_PASSWORD는 가입된 이메일에만 발급되므로 dev1@dandi.com 행으로 둔다
--     (비밀번호 재설정 confirm → reset 플로우를 바로 밟아볼 수 있다)

INSERT INTO code (id, email, code, type, attempt_count, send_count, expires_at)
VALUES (1, 'verify1@dandi.com', '111111', 'SIGNUP', 0, 1, NOW() + INTERVAL 5 MINUTE),
       (2, 'verify2@dandi.com', '222222', 'SIGNUP', 2, 3, NOW() + INTERVAL 5 MINUTE),
       (3, 'verify3@dandi.com', '333333', 'SIGNUP', 0, 1, NOW() - INTERVAL 10 MINUTE),
       (4, 'verify4@dandi.com', '444444', 'SIGNUP', 0, 5, NOW() + INTERVAL 5 MINUTE),
       (5, 'verify5@dandi.com', '555555', 'SIGNUP', 4, 2, NOW() - INTERVAL 1 DAY),
       (6, 'dev1@dandi.com', '666666', 'RESET_PASSWORD', 0, 1, NOW() + INTERVAL 5 MINUTE);

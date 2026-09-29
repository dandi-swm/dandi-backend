-- users (V0.21에서 provider 칼럼이 추가되어 v0.20/10-users.sql을 대체한다)
--
-- password는 전부 'Password123!' 이다. 자세한 설명은 v0.20/10-users.sql 참고.
--
-- provider / provider_user_id(V0.21): 가입 경로. provider는 NOT NULL이고 마이그레이션
-- 마지막 줄에서 DEFAULT를 떼므로 반드시 명시해야 한다(빠뜨리면 INSERT가 실패한다).
-- 여기 5건은 모두 이메일 가입이라 'EMAIL' / NULL이다.
-- 소셜 로그인이 붙으면 provider='KAKAO', email/password는 NULL, provider_user_id에
-- 카카오 회원번호를 넣은 행을 추가할 것 (UNIQUE는 (provider, email), (provider, provider_user_id)).

INSERT INTO users (id, email, password, provider, provider_user_id, is_temp_password, created_at)
VALUES (1, 'dev1@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg',
        'EMAIL', NULL, 0, NOW()),
       (2, 'dev2@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg',
        'EMAIL', NULL, 0, NOW()),
       (3, 'dev3@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg',
        'EMAIL', NULL, 0, NOW()),
       (4, 'dev4@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg',
        'EMAIL', NULL, 1, NOW()),
       (5, 'dev5@dandi.com',
        '$argon2id$v=19$m=19456,t=2,p=1$pL4sL6VO3Iq9+hc4qr05EQ$gwNSjBq4EmwKnNu4h9LKT1s1pJvhDjROvIaPgXp4oTg',
        'EMAIL', NULL, 0, NOW());

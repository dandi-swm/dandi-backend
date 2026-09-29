-- users (기본 정의)
--
-- password는 전부 'Password123!' 이다.
-- app.argon2 설정(salt 16 / hash 32 / parallelism 1 / memory 19456 / iterations 2)으로
-- 생성한 실제 Argon2id 해시라서 로그인 API가 그대로 통과한다.
-- 파라미터를 바꾸면 이 해시도 다시 만들어야 한다.
--
-- is_temp_password(V0.16): 비밀번호 찾기로 임시 비밀번호를 받은 상태인지.
-- user 4만 1로 두어 임시 비밀번호 분기를 밟아볼 수 있게 한다 (비밀번호 자체는 동일).

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

-- 적재 결과 요약

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

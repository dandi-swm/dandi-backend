-- V0.63__update_analysis_feedbacks_language.sql

-- V0.62 전에 만든 평가의 언어를 평가한 사용자의 기기 토큰 언어로 채운다.
--
-- 평가할 때의 언어는 남아 있지 않아서 지금 기기 언어로 대신한다. 그사이 기기 언어를 바꾼 사용자는 다르게 잡힐 수 있다.
-- 기기 토큰이 없거나 기기 토큰의 언어가 NULL인 사용자의 평가는 NULL로 남고, 원본 언어(KO)로 본다.
--
-- 컬럼 추가(V0.62)와 파일을 나눈 건, 이 UPDATE가 실패해도 이미 적용된 ALTER를 되돌리거나 repair하지 않고 다시 돌릴 수 있게 하려는 것이다.
-- language IS NULL인 행만 바꾸므로 다시 돌려도 이미 채운 값은 덮지 않는다.
UPDATE analysis_feedbacks AS f
    JOIN device_tokens AS d ON d.user_id = f.user_id
SET f.language = d.language
WHERE f.language IS NULL
  AND d.language IS NOT NULL;

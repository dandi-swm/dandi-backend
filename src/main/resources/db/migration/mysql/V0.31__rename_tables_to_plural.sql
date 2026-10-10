-- V0.31__rename_tables_to_plural.sql

-- 테이블 이름을 복수형으로 통일한다. (users, inquiries는 이미 복수형)
-- RENAME TABLE은 데이터·인덱스·FK 제약을 그대로 유지한다. 제약 이름(fk_cat_user 등)은 바뀌지 않는다.
-- ddl-auto: validate이므로 엔티티의 @Table(name)도 같은 배포에서 함께 바꿔야 한다.
RENAME TABLE cat TO cats,
             meal TO meals,
             icon TO icons,
             profile TO profiles,
             code TO codes,
             refresh_token TO refresh_tokens,
             device_token TO device_tokens;

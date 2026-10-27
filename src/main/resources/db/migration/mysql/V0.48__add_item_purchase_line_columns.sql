-- V0.48__add_item_purchase_line_columns.sql

-- 구매한 아이템이 우편과 마이룸에서 어느 구매 구성품(item_purchase_lines)으로 생겼는지 잇는다.
-- 환불할 때 이 연결로 회수할 우편 아이템이나 보유 아이템을 찾는다.
--
-- item_purchase_line_id가 NULL이면 보상으로 받은 것이다. 구성품 하나는 우편 아이템 하나, 보유 아이템 하나가 되므로 유니크다.
-- MySQL 유니크 인덱스는 NULL을 여러 개 허용하므로 보상 행은 막지 않는다.
-- 구매 우편은 상품마다 한 통이고, 그 안의 mail_items는 구성품마다 한 행(quantity 1)이다.
--
-- revoked_at은 환불로 회수한 시각이다.
--   mail_items       : 받기 전에 환불되었다. 받기에서 이 행을 건너뛴다.
--   owned_room_items : 받은 뒤 환불되었다. 사용자 삭제(deleted_at)와 달리 복구할 수 없다.
-- first_equipped_at은 처음 장착한 시각이고, NULL이면 "미착용"이라 환불할 수 있다.
-- 기존 보유 아이템은 모두 보상이라 환불 대상이 아니므로 값을 채우지 않는다.
--
-- 컬럼은 INSTANT로 먼저 추가하고, 유니크·FK는 INPLACE로 따로 건다.
-- foreign_key_checks = 0이면 FK 추가가 INPLACE로 처리되어 테이블을 복사하지 않는다.
-- 새 컬럼은 모두 NULL이라 기존 행 검사를 건너뛰어도 안전하다.
ALTER TABLE mail_items
    ADD COLUMN item_purchase_line_id BIGINT NULL,
    ADD COLUMN revoked_at TIMESTAMP NULL,
    ALGORITHM = INSTANT;

ALTER TABLE owned_room_items
    ADD COLUMN item_purchase_line_id BIGINT NULL,
    ADD COLUMN first_equipped_at TIMESTAMP NULL,
    ADD COLUMN revoked_at TIMESTAMP NULL,
    ALGORITHM = INSTANT;

SET foreign_key_checks = 0;

ALTER TABLE mail_items
    ADD CONSTRAINT uk_mail_items_item_purchase_line_id UNIQUE (item_purchase_line_id),
    ADD CONSTRAINT fk_mail_items_item_purchase_line FOREIGN KEY (item_purchase_line_id) REFERENCES item_purchase_lines (id) ON DELETE CASCADE,
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE owned_room_items
    ADD CONSTRAINT uk_owned_room_items_item_purchase_line_id UNIQUE (item_purchase_line_id),
    ADD CONSTRAINT fk_owned_room_items_item_purchase_line FOREIGN KEY (item_purchase_line_id) REFERENCES item_purchase_lines (id) ON DELETE CASCADE,
    ALGORITHM = INPLACE, LOCK = NONE;

SET foreign_key_checks = 1;

-- 하루 단위 코인 획득·소비 통계(Metabase)가 사용자 구분 없이 기간으로 읽으므로 created_at 인덱스를 추가한다.
ALTER TABLE coin_histories
    ADD INDEX idx_coin_histories_created_at (created_at),
    ALGORITHM = INPLACE, LOCK = NONE;

-- NULL 허용 컬럼과 인덱스 추가라 구버전은 이 컬럼들을 모른 채 그대로 동작한다.

-- V0.47__create_item_purchase_tables.sql

-- 상점 구매·환불 기록 테이블을 만든다.
--
-- 구매는 결제 한 번(item_purchases) → 상품 1개(item_purchase_products) → 구성품 하나(item_purchase_lines)로 나눈다.
-- 상품을 3개 사면 item_purchase_products가 3행이다. 환불을 상품 1개 단위로 계산하기 위해서다.
-- 가격은 모두 구매 시점 스냅샷이라, 뒤에 정가나 프로모션이 바뀌어도 환불 금액이 흔들리지 않는다.
-- 기록은 추가만 하고 수정·삭제하지 않으므로 updated_at / deleted_at은 두지 않는다.
--
-- 사용자 행이 지워지면 구매 기록도 함께 지워지도록 사용자에서 이어지는 FK는 모두 ON DELETE CASCADE다.

-- 결제 한 번. price는 결제 총액(코인 차감액)이다. created_at은 7일 환불 기한의 기준이다.
--
-- 같은 Idempotency-Key로 다시 오면 처음 결과를 돌려준다. 구매 트랜잭션이 코인 행을 먼저 잠그므로
-- 같은 키 요청은 차례로 처리되고, 유니크 제약은 그 잠금이 빠졌을 때의 안전망이다.
-- (user_id, idempotency_key) 유니크 인덱스가 fk_item_purchases_user의 인덱스를 겸한다.
CREATE TABLE item_purchases
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    price           INT         NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_item_purchases_user_id_idempotency_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_item_purchases_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- 결제 안의 상품 1개. 환불 단위다.
-- list_price는 1개 정가, price는 1개 실제 결제가다. 단품 환불은 price를 그대로 돌려준다.
-- promotion_id는 적용된 할인이고 NULL이면 정가다. 계산에는 쓰지 않고 프로모션별 매출과 CS 확인에 쓴다.
-- 프로모션은 끝나거나 할인율이 바뀌므로 가격만으로는 나중에 어떤 프로모션이었는지 알 수 없어 저장한다.
CREATE TABLE item_purchase_products
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_purchase_id BIGINT NOT NULL,
    product_id       BIGINT NOT NULL,
    promotion_id     BIGINT NULL,
    list_price       INT    NOT NULL,
    price            INT    NOT NULL,
    CONSTRAINT fk_item_purchase_products_item_purchase FOREIGN KEY (item_purchase_id) REFERENCES item_purchases (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_purchase_products_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_item_purchase_products_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id)
);

-- 상품 1개 안의 구성품 하나. 단품은 1행, 세트는 구성품 수만큼이다.
-- 구매 우편의 아이템(mail_items)과 받은 뒤의 보유 아이템(owned_room_items)이 이 행을 가리킨다.
-- list_price는 구매 시점 구성품 정가이고, 세트를 일부만 환불할 때 남는 구성품을 정가로 계산하는 기준이다.
CREATE TABLE item_purchase_lines
(
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_purchase_product_id BIGINT NOT NULL,
    item_id                  BIGINT NOT NULL,
    list_price               INT    NOT NULL,
    CONSTRAINT fk_item_purchase_lines_item_purchase_product FOREIGN KEY (item_purchase_product_id) REFERENCES item_purchase_products (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_purchase_lines_item FOREIGN KEY (item_id) REFERENCES items (id)
);

-- 구매 시도. 성공·실패 모두 한 행이다.
-- 성공은 구매 트랜잭션 안에서, 실패는 구매 트랜잭션이 롤백된 뒤 별도 트랜잭션으로 저장한다.
--
-- requested_products는 앱이 보낸 상품 목록을 그대로 저장한다. 없는 상품 id도 남기려고 FK를 걸지 않는다.
-- outcome은 결과(성공, 같은 키 재응답, 장바구니 불일치, 가격 변경, 잔액 부족 등)다.
-- item_purchase_id는 성공했을 때만 채운다. app_version은 앱 버그와 조작된 요청을 구분하는 데 쓴다.
CREATE TABLE item_purchase_attempts
(
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT      NOT NULL,
    requested_products    JSON        NOT NULL,
    requested_total_price INT         NOT NULL,
    idempotency_key       VARCHAR(64) NOT NULL,
    outcome               VARCHAR(30) NOT NULL,
    item_purchase_id      BIGINT      NULL,
    app_version           VARCHAR(20) NULL,
    created_at            TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_item_purchase_attempts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_purchase_attempts_item_purchase FOREIGN KEY (item_purchase_id) REFERENCES item_purchases (id) ON DELETE CASCADE
);

-- 환불 한 번. 운영자가 CS로 처리하고, 상품 1개를 여러 번 나눠 환불할 수 있다.
-- 환불은 취소하지 않으므로 상태 컬럼이 없고, 행이 있으면 환불된 것이다.
--
-- coin_quantity는 이번에 돌려준 코인이다. 세트는 할인을 취소한 금액이라 구성품 정가 합과 다를 수 있다.
-- 세트의 다음 환불 금액은 같은 상품 1개의 이전 환불 coin_quantity 합을 빼서 계산한다.
CREATE TABLE item_purchase_refunds
(
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_purchase_product_id BIGINT       NOT NULL,
    admin_id                 BIGINT       NOT NULL,
    coin_quantity            INT          NOT NULL,
    reason                   VARCHAR(255) NOT NULL,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_item_purchase_refunds_item_purchase_product FOREIGN KEY (item_purchase_product_id) REFERENCES item_purchase_products (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_purchase_refunds_admin FOREIGN KEY (admin_id) REFERENCES admins (id)
);

-- 환불에 포함된 구성품. item_purchase_line_id 유니크로 같은 구성품을 두 번 환불하지 못하게 DB에서 막는다.
CREATE TABLE item_purchase_refund_lines
(
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_purchase_refund_id BIGINT NOT NULL,
    item_purchase_line_id   BIGINT NOT NULL,
    CONSTRAINT uk_item_purchase_refund_lines_item_purchase_line_id UNIQUE (item_purchase_line_id),
    CONSTRAINT fk_item_purchase_refund_lines_item_purchase_refund FOREIGN KEY (item_purchase_refund_id) REFERENCES item_purchase_refunds (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_purchase_refund_lines_item_purchase_line FOREIGN KEY (item_purchase_line_id) REFERENCES item_purchase_lines (id) ON DELETE CASCADE
);

-- 테이블 추가라 구버전은 이 테이블을 모른 채 그대로 동작하고, 롤백해도 남은 테이블이 기동을 막지 않는다.

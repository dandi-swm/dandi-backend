-- V0.46__create_shop_tables.sql

-- 상점의 상품·프로모션·배너·장바구니 테이블을 만든다.

-- 상품(판매 단위). 단품은 구성품 하나, 세트는 여러 개다. 앱의 productId가 이 id다.
-- 아이템(items)은 게임 안의 물건이고, 상품은 그 물건을 파는 단위라 테이블을 나눈다.
--
-- price는 1개 정가다. 할인가는 저장하지 않고 진행 중인 프로모션의 할인율로 계산한다.
-- category는 RoomItemCategory 값이고, 세트는 운영자가 정한다. 탭과 분류 칩을 거르는 기준이다.
-- representative_item_id는 카드에 그릴 그림이다. 단품은 그 아이템, 세트는 대표 구성품이다.
--
-- sale_starts_at은 신상순 정렬 기준이고, 미래 시각이면 예약 등록이다.
-- sale_ends_at이 NULL이면 종료일이 없다. 판매 중지는 행을 지우지 않고 지금 시각을 넣는다.
-- 구매·환불 기록이 상품을 가리키므로 상품은 지우지 않는다.
-- purchase_limit은 1인 구매 한도이고 NULL이면 무제한이다. 환불한 구매는 세지 않는다.
-- sales_count는 최근 판매량이다. Internal 배치가 갱신하고 판매량순 정렬과 인기 순위에 쓴다.
--
-- 상품 수가 수백 개 수준이라 목록 조회용 인덱스는 두지 않는다.
CREATE TABLE products
(
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    category               VARCHAR(20)  NOT NULL,
    name                   VARCHAR(100) NOT NULL,
    representative_item_id BIGINT       NOT NULL,
    price                  INT          NOT NULL,
    is_limited             BOOLEAN      NOT NULL DEFAULT FALSE,
    sale_starts_at         TIMESTAMP    NOT NULL,
    sale_ends_at           TIMESTAMP    NULL,
    purchase_limit         INT          NULL,
    sales_count            INT          NOT NULL DEFAULT 0,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_products_representative_item FOREIGN KEY (representative_item_id) REFERENCES items (id)
);

-- 상품의 구성품. 단품은 1행, 세트는 구성품 수만큼이다.
-- list_price는 구성품 정가이고, 세트를 일부만 환불할 때 남는 구성품을 정가로 계산하는 기준이다.
-- 만든 뒤에는 바꾸지 않는다. 구성을 바꾸려면 새 상품을 만든다.
CREATE TABLE product_items
(
    product_id BIGINT NOT NULL,
    item_id    BIGINT NOT NULL,
    list_price INT    NOT NULL,
    PRIMARY KEY (product_id, item_id),
    CONSTRAINT fk_product_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_product_items_item FOREIGN KEY (item_id) REFERENCES items (id)
);

-- 프로모션. 신입 집사 환영·첫 구매 혜택·오늘의 할인처럼 기간과 대상을 정해 상품을 묶어 판다.
-- 앱 링크 /shop/promotions/{promotionId}의 값이 이 id다.
--
-- category가 대상을 정한다. 대상 판정 기준값(가입 후 일수 등)은 설정에 둔다.
-- discount_rate는 0~100 정수 퍼센트이고, NULL이면 할인 없이 상품만 묶는다.
-- 가격은 진행 중이고 사용자가 대상인 프로모션 중 할인율이 가장 큰 것 하나만 적용한다.
-- ends_at이 NULL이면 종료일이 없다. 구매 기록이 프로모션을 가리키므로 지우지 않고 ends_at으로 끝낸다.
CREATE TABLE promotions
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    category      VARCHAR(20)  NOT NULL,
    discount_rate INT          NULL,
    starts_at     TIMESTAMP    NOT NULL,
    ends_at       TIMESTAMP    NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 프로모션에 묶인 상품. display_order는 프로모션 화면의 진열 순서다.
CREATE TABLE promotion_products
(
    promotion_id  BIGINT NOT NULL,
    product_id    BIGINT NOT NULL,
    display_order INT    NOT NULL,
    PRIMARY KEY (promotion_id, product_id),
    CONSTRAINT fk_promotion_products_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id),
    CONSTRAINT fk_promotion_products_product FOREIGN KEY (product_id) REFERENCES products (id)
);

-- 추천 탭 배너. 프로모션 하나를 알리고, 누르면 그 프로모션 화면을 연다. 앱의 bannerId가 이 id다.
--
-- 배너를 2주~한 달마다 바꾸므로 배포 없이 어드민에서 바꾸도록 테이블에 둔다.
-- title·description은 평문이고 서버가 RichText로 감싼다. *_color는 SDUI 색 토큰(contentPrimary 등)이다.
-- 모서리(놓인 자리가 정함)와 가격표(프로모션 상품의 정가 합·결제가 합)는 계산하는 값이라 저장하지 않는다.
-- ends_at이 NULL이면 내릴 때까지 노출한다. 내리기는 ends_at에 지금 시각을 넣는다.
-- admin_id는 마지막으로 바꾼 운영자다.
CREATE TABLE shop_banners
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    promotion_id      BIGINT       NOT NULL,
    image_key         VARCHAR(512) NOT NULL,
    title             VARCHAR(100) NOT NULL,
    description       VARCHAR(255) NULL,
    title_color       VARCHAR(30)  NOT NULL,
    description_color VARCHAR(30)  NULL,
    display_order     INT          NOT NULL,
    starts_at         TIMESTAMP    NOT NULL,
    ends_at           TIMESTAMP    NULL,
    admin_id          BIGINT       NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_shop_banners_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id),
    CONSTRAINT fk_shop_banners_admin FOREIGN KEY (admin_id) REFERENCES admins (id)
);

-- 장바구니 항목. 같은 상품은 한 행이고 수량으로 센다.
-- is_selected는 결제 정보에 합산할지 여부다. created_at은 장바구니 정렬 기준이다.
-- 수량 상한(상품당·전체)은 설정에 두고 서버가 검증한다.
--
-- (user_id, product_id) 유니크 인덱스가 fk_cart_items_user의 인덱스를 겸한다.
CREATE TABLE cart_items
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT    NOT NULL,
    product_id  BIGINT    NOT NULL,
    quantity    INT       NOT NULL,
    is_selected BOOLEAN   NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_cart_items_user_id_product_id UNIQUE (user_id, product_id),
    CONSTRAINT fk_cart_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id)
);

-- 테이블 추가라 구버전은 이 테이블을 모른 채 그대로 동작하고, 롤백해도 남은 테이블이 기동을 막지 않는다.

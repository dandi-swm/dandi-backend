package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class ShopErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "api.shop.productNotFound", "요청한 productId가 판매 중인 상품에 없습니다."),
    PRODUCT_NOT_FOR_SALE(HttpStatus.CONFLICT, "api.shop.productNotForSale", "판매 기간이 아닌 상품입니다."),
    PROMOTION_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "api.shop.promotionNotFound",
        "요청한 promotionId가 진행 중인 프로모션에 없거나, 대상이 아니거나, 노출 중인 배너가 없습니다.",
    ),
    ALREADY_OWNED(HttpStatus.CONFLICT, "api.shop.alreadyOwned", "이미 가진 방 배경입니다."),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "api.shop.cartItemNotFound", "장바구니에 없는 상품입니다."),
    CART_QUANTITY_EXCEEDED(HttpStatus.CONFLICT, "api.shop.cartQuantityExceeded", "이 상품을 담을 수 있는 수량을 넘었습니다."),
    CART_FULL(HttpStatus.CONFLICT, "api.shop.cartFull", "장바구니에 담을 수 있는 전체 수량을 넘었습니다."),
    CART_MISMATCH(HttpStatus.CONFLICT, "api.shop.cartMismatch", "구매할 상품이 장바구니에 담긴 상품·수량과 다릅니다."),
    PRICE_CHANGED(HttpStatus.CONFLICT, "api.shop.priceChanged", "상품 가격이 바뀌었습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "api.shop.insufficientBalance", "코인이 부족합니다."),
    PURCHASE_IN_PROGRESS(HttpStatus.CONFLICT, "api.shop.purchaseInProgress", "처리 중인 구매가 있습니다."),
}

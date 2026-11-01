package com.dandi.nyummy.shop.enum

/**
 * 구매 시도의 결과. 거부 사유는 응답한 에러 코드와 짝을 이룬다.
 */
enum class ItemPurchaseOutcome {
    /** 구매를 만들었다 */
    SUCCEEDED,

    /** 같은 Idempotency-Key의 처음 결과를 다시 돌려줬다 */
    REPLAYED,

    /** 같은 Idempotency-Key로 다른 상품·수량·가격을 보냈다 */
    IDEMPOTENCY_KEY_REUSED,

    PRODUCT_NOT_FOUND,
    PRODUCT_NOT_FOR_SALE,
    ALREADY_OWNED,
    PURCHASE_LIMIT_EXCEEDED,
    CART_MISMATCH,
    PRICE_CHANGED,
    INSUFFICIENT_BALANCE,

    /** 코인 행 잠금을 제시간에 얻지 못했다 */
    PURCHASE_IN_PROGRESS,
}

package com.dandi.nyummy.shop.enum

import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ShopErrorCode

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

    /** 코인 행 잠금을 제시간에 얻지 못했거나 교착으로 롤백되었다 */
    PURCHASE_IN_PROGRESS,

    /**
     * 거부 사유가 아닌 예외로 실패했다. 데이터 오류나 DB 장애처럼 정상 상태에서는 일어나지 않는다.
     * 대부분 롤백되지만 커밋 중에 연결이 끊기면 구매가 남았을 수 있다
     */
    UNEXPECTED_ERROR,
    ;

    companion object {
        /** 거부 사유의 에러 코드에 맞는 결과. 구매 시도의 거부 사유가 아니면 [UNEXPECTED_ERROR]다 */
        fun from(errorCode: ErrorCode): ItemPurchaseOutcome = when (errorCode) {
            ShopErrorCode.IDEMPOTENCY_KEY_REUSED -> IDEMPOTENCY_KEY_REUSED
            ShopErrorCode.PRODUCT_NOT_FOUND -> PRODUCT_NOT_FOUND
            ShopErrorCode.PRODUCT_NOT_FOR_SALE -> PRODUCT_NOT_FOR_SALE
            ShopErrorCode.ALREADY_OWNED -> ALREADY_OWNED
            ShopErrorCode.PURCHASE_LIMIT_EXCEEDED -> PURCHASE_LIMIT_EXCEEDED
            ShopErrorCode.CART_MISMATCH -> CART_MISMATCH
            ShopErrorCode.PRICE_CHANGED -> PRICE_CHANGED
            ShopErrorCode.INSUFFICIENT_BALANCE -> INSUFFICIENT_BALANCE
            else -> UNEXPECTED_ERROR
        }
    }
}

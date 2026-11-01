package com.dandi.nyummy.shop.dto

import com.dandi.nyummy.exception.errorcode.ShopErrorCode

/**
 * 사용자가 상품을 지금 살 수 있는지.
 *
 * @property unavailableErrorCode 살 수 없는 사유. null이면 살 수 있다
 * @property remainingPurchaseLimit 1인 구매 한도까지 더 살 수 있는 개수. null이면 한도가 없다
 */
data class ProductAvailabilityResult(val unavailableErrorCode: ShopErrorCode?, val remainingPurchaseLimit: Int?) {
    /**
     * [quantity]개를 살 수 없는 사유. 상품을 살 수 있어도 남은 한도보다 많이 사려면 [ShopErrorCode.PURCHASE_LIMIT_EXCEEDED]다.
     * 운영자가 한도를 줄이면 장바구니에 담긴 수량이 남은 한도보다 많을 수 있다.
     */
    fun getUnavailableErrorCode(quantity: Int): ShopErrorCode? = unavailableErrorCode
        ?: ShopErrorCode.PURCHASE_LIMIT_EXCEEDED.takeIf {
            remainingPurchaseLimit != null &&
                quantity > remainingPurchaseLimit
        }
}

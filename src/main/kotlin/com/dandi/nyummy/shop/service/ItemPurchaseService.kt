package com.dandi.nyummy.shop.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseResponse
import org.springframework.stereotype.Service

/**
 * 상점 구매. 지금은 저장하지 않고, [ShopStubData]의 고정 상품·잔액으로 검증한 결과만 돌려준다.
 *
 * 장바구니를 저장하지 않아 앱이 담거나 수량을 바꾼 상품이 고정 장바구니와 달라지므로, 장바구니 일치는 아직 확인하지 않는다.
 * 같은 요청이면 같은 결과가 나오므로 같은 Idempotency-Key로 다시 보내도 처음과 같은 응답이 온다.
 */
@Service
class ItemPurchaseService {

    companion object {
        private const val STUB_ITEM_PURCHASE_ID = 9001L
    }

    /**
     * 장바구니에서 선택한 상품을 코인으로 산다.
     *
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 같은 상품을 두 번 보낸 경우
     * @throws BusinessException [ShopErrorCode.PRODUCT_NOT_FOUND] 없는 상품인 경우
     * @throws BusinessException [ShopErrorCode.PRODUCT_NOT_FOR_SALE] 판매 기간이 아닌 경우
     * @throws BusinessException [ShopErrorCode.ALREADY_OWNED] 이미 가진 방 배경인 경우
     * @throws BusinessException [ShopErrorCode.PRICE_CHANGED] 화면에서 본 가격과 지금 가격이 다른 경우
     * @throws BusinessException [ShopErrorCode.INSUFFICIENT_BALANCE] 결제할 코인이 잔액보다 많은 경우
     */
    fun createItemPurchase(
        userId: Long,
        idempotencyKey: String,
        request: CreateItemPurchaseRequest,
    ): ItemPurchaseResponse {
        val productIds = request.products.map { it.productId }
        if (productIds.size != productIds.toSet().size) throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE)

        request.products.forEach { requested ->
            val product = ShopStubData.productsById[requested.productId]
                ?: throw BusinessException(ShopErrorCode.PRODUCT_NOT_FOUND)
            if (!product.isOnSale) throw BusinessException(ShopErrorCode.PRODUCT_NOT_FOR_SALE)
            if (product.isOwnedBackground) throw BusinessException(ShopErrorCode.ALREADY_OWNED)
            if (product.purchasePrice != requested.purchasePrice) throw BusinessException(ShopErrorCode.PRICE_CHANGED)
        }

        val purchasePrice = request.products.sumOf { it.purchasePrice * it.quantity }
        if (purchasePrice > ShopStubData.BALANCE) throw BusinessException(ShopErrorCode.INSUFFICIENT_BALANCE)

        return ItemPurchaseResponse(
            itemPurchaseId = STUB_ITEM_PURCHASE_ID,
            quantity = request.products.sumOf { it.quantity },
            purchasePrice = purchasePrice,
            balance = ShopStubData.BALANCE - purchasePrice,
            cartItemCount = ShopStubData.cartItems.count { it.productId !in productIds },
        )
    }
}

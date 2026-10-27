package com.dandi.nyummy.shop.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.shop.dto.CartItemResponse
import com.dandi.nyummy.shop.dto.CartResponse
import com.dandi.nyummy.shop.dto.CartSummaryResponse
import com.dandi.nyummy.shop.dto.CreateCartItemRequest
import com.dandi.nyummy.shop.dto.CreatedCartItemResponse
import com.dandi.nyummy.shop.dto.UpdateCartItemQuantityRequest
import com.dandi.nyummy.shop.dto.UpdateCartSelectionRequest
import com.dandi.nyummy.shop.service.ShopStubData.StubCartItem
import com.dandi.nyummy.shop.service.ShopStubData.StubProduct
import com.dandi.nyummy.shop.service.ShopStubData.toShopItemResponse
import org.springframework.stereotype.Service

/**
 * 장바구니. 지금은 저장하지 않고, 요청마다 [ShopStubData]의 고정 장바구니에 그 요청을 반영한 결과만 돌려준다.
 *
 * 결제 정보와 담을 수 있는 수량은 실제 규칙(선택·구매 가능 항목만 합산, 상품당 10개·방 배경 1개, 장바구니 전체 30개)으로 계산한다.
 */
@Service
class CartService {

    fun getCart(userId: Long): CartResponse = createCart(ShopStubData.cartItems)

    /**
     * 상품을 [CreateCartItemRequest.quantity]개 더 담는다. 이미 담긴 상품이면 기존 수량에 더하고, 담은 상품은 선택 상태가 된다.
     *
     * @throws BusinessException [ShopErrorCode.PRODUCT_NOT_FOUND] 없는 상품인 경우
     * @throws BusinessException [ShopErrorCode.PRODUCT_NOT_FOR_SALE] 판매 기간이 아닌 경우
     * @throws BusinessException [ShopErrorCode.ALREADY_OWNED] 이미 가진 방 배경인 경우
     * @throws BusinessException [ShopErrorCode.CART_QUANTITY_EXCEEDED] 상품당 상한을 넘는 경우
     * @throws BusinessException [ShopErrorCode.CART_FULL] 장바구니 전체 상한을 넘는 경우
     */
    fun createCartItem(userId: Long, request: CreateCartItemRequest): CreatedCartItemResponse {
        val product = ShopStubData.productsById[request.productId]
            ?: throw BusinessException(ShopErrorCode.PRODUCT_NOT_FOUND)
        if (!product.isOnSale) throw BusinessException(ShopErrorCode.PRODUCT_NOT_FOR_SALE)
        if (product.isOwnedBackground) throw BusinessException(ShopErrorCode.ALREADY_OWNED)

        val cartItems = ShopStubData.cartItems
        val existingQuantity = cartItems.find { it.productId == request.productId }?.quantity ?: 0
        val newQuantity = existingQuantity + request.quantity
        validateQuantity(product, newQuantity, otherQuantity = cartItems.sumOf { it.quantity } - existingQuantity)

        return CreatedCartItemResponse(
            productId = request.productId,
            quantity = newQuantity,
            cartItemCount = cartItems.size + if (existingQuantity == 0) 1 else 0,
        )
    }

    /**
     * 장바구니에 담긴 상품의 수량을 [UpdateCartItemQuantityRequest.quantity]로 맞추고 장바구니 전체를 반환한다.
     *
     * @throws BusinessException [ShopErrorCode.CART_ITEM_NOT_FOUND] 장바구니에 없는 상품인 경우
     * @throws BusinessException [ShopErrorCode.CART_QUANTITY_EXCEEDED] 구매 불가 상품이거나 상품당 상한을 넘는 경우
     * @throws BusinessException [ShopErrorCode.CART_FULL] 장바구니 전체 상한을 넘는 경우
     */
    fun updateCartItemQuantity(userId: Long, productId: Long, request: UpdateCartItemQuantityRequest): CartResponse {
        val cartItems = ShopStubData.cartItems
        val cartItem = cartItems.find { it.productId == productId }
            ?: throw BusinessException(ShopErrorCode.CART_ITEM_NOT_FOUND)
        val product = ShopStubData.productsById.getValue(productId)
        if (!product.canPurchase) throw BusinessException(ShopErrorCode.CART_QUANTITY_EXCEEDED)
        validateQuantity(product, request.quantity, otherQuantity = cartItems.sumOf { it.quantity } - cartItem.quantity)

        return createCart(cartItems.map { if (it.productId == productId) it.copy(quantity = request.quantity) else it })
    }

    /**
     * 선택된 상품 목록을 요청 목록으로 맞추고 장바구니 전체를 반환한다. 장바구니에 없거나 구매 불가인 id는 무시한다.
     */
    fun updateCartSelection(userId: Long, request: UpdateCartSelectionRequest): CartResponse =
        createCart(ShopStubData.cartItems.map { it.copy(isSelected = it.productId in request.productIds) })

    /**
     * 상품을 장바구니에서 빼고 장바구니 전체를 반환한다. 장바구니에 없는 id는 무시한다.
     */
    fun deleteCartItems(userId: Long, productIds: List<Long>): CartResponse =
        createCart(ShopStubData.cartItems.filterNot { it.productId in productIds })

    private fun validateQuantity(product: StubProduct, quantity: Int, otherQuantity: Int) {
        if (quantity > product.maxQuantityPerProduct) throw BusinessException(ShopErrorCode.CART_QUANTITY_EXCEEDED)
        if (otherQuantity + quantity > ShopStubData.MAX_CART_QUANTITY) throw BusinessException(ShopErrorCode.CART_FULL)
    }

    // 결제 정보는 선택됐고 구매 가능한 항목만 합친다
    private fun createCart(cartItems: List<StubCartItem>): CartResponse {
        val totalQuantity = cartItems.sumOf { it.quantity }
        val cartItemResponses = cartItems.map { cartItem ->
            val product = ShopStubData.productsById.getValue(cartItem.productId)
            val maxQuantity = if (product.canPurchase) {
                minOf(
                    product.maxQuantityPerProduct,
                    ShopStubData.MAX_CART_QUANTITY - (totalQuantity - cartItem.quantity),
                )
            } else {
                0
            }
            CartItemResponse(
                shopItem = product.toShopItemResponse(),
                quantity = cartItem.quantity,
                maxQuantity = maxQuantity,
                isSelected = cartItem.isSelected && product.canPurchase,
                canPurchase = product.canPurchase,
                purchasePrice = product.purchasePrice,
                unavailableReason = createUnavailableReason(product),
            )
        }

        val selectedItems = cartItemResponses.filter { it.isSelected }
        val listPrice = selectedItems.sumOf {
            ShopStubData.productsById.getValue(it.shopItem.productId).listPrice * it.quantity
        }
        val purchasePrice = selectedItems.sumOf { it.purchasePrice * it.quantity }

        return CartResponse(
            cartItems = cartItemResponses,
            selectedItemCount = selectedItems.size,
            summary = CartSummaryResponse(
                quantity = selectedItems.sumOf { it.quantity },
                listPrice = listPrice,
                discountPrice = listPrice - purchasePrice,
                purchasePrice = purchasePrice,
                balance = ShopStubData.BALANCE,
                balanceAfter = ShopStubData.BALANCE - purchasePrice,
            ),
        )
    }

    private fun createUnavailableReason(product: StubProduct): String? = when {
        !product.isOnSale -> "판매가 끝났어요"
        product.isOwnedBackground -> "이미 가진 방 배경이에요"
        else -> null
    }
}

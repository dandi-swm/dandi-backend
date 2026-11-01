package com.dandi.nyummy.shop.service

import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.calculator.calculateMaxDiscountRates
import com.dandi.nyummy.shop.calculator.calculatePurchasePrice
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.dto.CartItemResponse
import com.dandi.nyummy.shop.dto.CartResponse
import com.dandi.nyummy.shop.dto.CartSummaryResponse
import com.dandi.nyummy.shop.dto.CreateCartItemRequest
import com.dandi.nyummy.shop.dto.CreatedCartItemResponse
import com.dandi.nyummy.shop.dto.ShopItemResponse
import com.dandi.nyummy.shop.dto.UpdateCartItemQuantityRequest
import com.dandi.nyummy.shop.dto.UpdateCartSelectionRequest
import com.dandi.nyummy.shop.entity.CartItem
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.repository.CartItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 장바구니. 가격은 저장하지 않고, 조회·변경할 때마다 그 시점의 결제가와 구매 가능 여부를 다시 계산한다.
 *
 * 결제 정보는 선택됐고 구매 가능한 항목만 합친다. 담을 수 있는 수량은 상품당 [ShopProperties.maxQuantityPerProduct]개
 * (방 배경은 1개), 1인 구매 한도까지 남은 개수, 장바구니 전체 [ShopProperties.maxCartQuantity]개 중 남은 수량 가운데 가장 작은 값이다.
 *
 * 장바구니를 바꾸는 메서드는 먼저 사용자의 코인 행을 잠가 같은 사용자의 장바구니 변경·구매를 하나씩 처리한다.
 * 그래서 "담기"를 연달아 눌러도 같은 상품이 두 행으로 들어가지 않고(uk_cart_items_user_id_product_id), 전체 상한도 넘지 않는다.
 * 잠금이 트랜잭션의 첫 문장이어야 그 뒤의 조회가 잠금을 얻은 뒤의 데이터를 본다.
 */
@Service
class CartService(
    private val clock: Clock,
    private val shopItemService: ShopItemService,
    private val coinService: CoinService,
    private val shopProperties: ShopProperties,
    private val cartItemRepository: CartItemRepository,
    private val productRepository: ProductRepository,
    private val promotionProductRepository: PromotionProductRepository,
) {

    companion object {
        // 같은 방 배경은 하나만 가질 수 있다
        private const val MAX_BACKGROUND_QUANTITY = 1
    }

    @Transactional(readOnly = true)
    fun getCart(userId: Long): CartResponse {
        val cartItems = cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)

        return createCart(getPricedCartItems(userId, cartItems, clock.instant()), coinService.getBalance(userId))
    }

    /**
     * 상품을 [CreateCartItemRequest.quantity]개 더 담는다. 이미 담긴 상품이면 기존 수량에 더하고, 담은 상품은 선택 상태가 된다.
     *
     * @throws BusinessException [ShopErrorCode.PRODUCT_NOT_FOUND] 없는 상품인 경우
     * @throws BusinessException [ShopErrorCode.PRODUCT_NOT_FOR_SALE] 판매 기간이 아닌 경우
     * @throws BusinessException [ShopErrorCode.ALREADY_OWNED] 이미 가진 방 배경인 경우
     * @throws BusinessException [ShopErrorCode.PURCHASE_LIMIT_EXCEEDED] 1인 구매 한도까지 이미 산 경우
     * @throws BusinessException [ShopErrorCode.CART_QUANTITY_EXCEEDED] 상품당 상한이나 1인 구매 한도를 넘는 경우
     * @throws BusinessException [ShopErrorCode.CART_FULL] 장바구니 전체 상한을 넘는 경우
     */
    @Transactional
    fun createCartItem(userId: Long, request: CreateCartItemRequest): CreatedCartItemResponse {
        coinService.lockCoin(userId)
        val product = productRepository.findByIdOrNull(request.productId)
            ?: throw BusinessException(ShopErrorCode.PRODUCT_NOT_FOUND)
        val availability = shopItemService.getProductAvailabilities(userId, listOf(product), clock.instant())
            .getValue(product.id)
        availability.unavailableErrorCode?.let { throw BusinessException(it) }

        val cartItems = cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)
        val cartItem = cartItems.find { it.productId == product.id }
        val existingQuantity = cartItem?.quantity ?: 0
        val quantity = existingQuantity + request.quantity
        validateQuantity(
            product,
            availability.remainingPurchaseLimit,
            quantity,
            otherQuantity = cartItems.sumOf { it.quantity } - existingQuantity,
        )

        if (cartItem == null) {
            cartItemRepository.save(CartItem(userId = userId, productId = product.id, quantity = quantity))
        } else {
            cartItem.updateQuantity(quantity)
            cartItem.updateIsSelected(true)
        }

        return CreatedCartItemResponse(
            productId = product.id,
            quantity = quantity,
            cartItemCount = cartItems.size + if (cartItem == null) 1 else 0,
        )
    }

    /**
     * 장바구니에 담긴 상품의 수량을 [UpdateCartItemQuantityRequest.quantity]로 맞추고 장바구니 전체를 반환한다.
     *
     * @throws BusinessException [ShopErrorCode.CART_ITEM_NOT_FOUND] 장바구니에 없는 상품인 경우
     * @throws BusinessException [ShopErrorCode.PRODUCT_NOT_FOR_SALE] 판매 기간이 끝난 상품인 경우
     * @throws BusinessException [ShopErrorCode.ALREADY_OWNED] 이미 가진 방 배경인 경우
     * @throws BusinessException [ShopErrorCode.PURCHASE_LIMIT_EXCEEDED] 1인 구매 한도까지 이미 산 경우
     * @throws BusinessException [ShopErrorCode.CART_QUANTITY_EXCEEDED] 상품당 상한이나 1인 구매 한도를 넘는 경우
     * @throws BusinessException [ShopErrorCode.CART_FULL] 장바구니 전체 상한을 넘는 경우
     */
    @Transactional
    fun updateCartItemQuantity(userId: Long, productId: Long, request: UpdateCartItemQuantityRequest): CartResponse {
        val coin = coinService.lockCoin(userId)
        val cartItems = cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)
        val pricedCartItems = getPricedCartItems(userId, cartItems, clock.instant())
        val target = pricedCartItems.find { it.cartItem.productId == productId }
            ?: throw BusinessException(ShopErrorCode.CART_ITEM_NOT_FOUND)
        // 구매할 수 없는 항목은 수량을 바꿀 수 없고, 그 이유를 그대로 돌려준다
        target.unavailableErrorCode?.let { throw BusinessException(it) }
        validateQuantity(
            target.product,
            target.remainingPurchaseLimit,
            request.quantity,
            otherQuantity = cartItems.sumOf { it.quantity } - target.cartItem.quantity,
        )

        target.cartItem.updateQuantity(request.quantity)

        return createCart(pricedCartItems, coin.balance)
    }

    /**
     * 선택된 상품 목록을 요청 목록으로 맞추고 장바구니 전체를 반환한다. 장바구니에 없는 id는 무시하고, 구매 불가 항목은 선택을 푼다.
     */
    @Transactional
    fun updateCartSelection(userId: Long, request: UpdateCartSelectionRequest): CartResponse {
        val coin = coinService.lockCoin(userId)
        val cartItems = cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)
        val pricedCartItems = getPricedCartItems(userId, cartItems, clock.instant())

        pricedCartItems.forEach {
            it.cartItem.updateIsSelected(it.canPurchase && it.cartItem.productId in request.productIds)
        }

        return createCart(pricedCartItems, coin.balance)
    }

    /**
     * 상품을 장바구니에서 빼고 장바구니 전체를 반환한다. 장바구니에 없는 id는 무시한다.
     */
    @Transactional
    fun deleteCartItems(userId: Long, productIds: List<Long>): CartResponse {
        val coin = coinService.lockCoin(userId)
        val (deletedCartItems, cartItems) = cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)
            .partition { it.productId in productIds }

        cartItemRepository.deleteAll(deletedCartItems)

        return createCart(getPricedCartItems(userId, cartItems, clock.instant()), coin.balance)
    }

    private fun validateQuantity(product: Product, remainingPurchaseLimit: Int?, quantity: Int, otherQuantity: Int) {
        if (quantity > getMaxQuantityPerProduct(product, remainingPurchaseLimit)) {
            throw BusinessException(ShopErrorCode.CART_QUANTITY_EXCEEDED)
        }
        if (otherQuantity + quantity > shopProperties.maxCartQuantity) throw BusinessException(ShopErrorCode.CART_FULL)
    }

    private fun getMaxQuantityPerProduct(product: Product, remainingPurchaseLimit: Int?): Int {
        val maxQuantity = when (product.category) {
            RoomItemCategory.BACKGROUND -> MAX_BACKGROUND_QUANTITY
            else -> shopProperties.maxQuantityPerProduct
        }
        return minOf(maxQuantity, remainingPurchaseLimit ?: maxQuantity)
    }

    // 장바구니 화면에 보일 구매 불가 문구
    private fun createUnavailableReason(errorCode: ShopErrorCode?): String? = when (errorCode) {
        null -> null
        ShopErrorCode.PRODUCT_NOT_FOR_SALE -> "판매가 끝났어요"
        ShopErrorCode.ALREADY_OWNED -> "이미 가진 방 배경이에요"
        ShopErrorCode.PURCHASE_LIMIT_EXCEEDED -> "구매 한도를 넘었어요"
        else -> error("장바구니 구매 불가 사유가 아닌 에러 코드입니다: $errorCode")
    }

    // 장바구니 항목마다 상품·카드·결제가·구매 불가 사유를 붙인다. 상품은 지우지 않으므로 판매가 끝난 상품도 찾는다.
    private fun getPricedCartItems(userId: Long, cartItems: List<CartItem>, now: Instant): List<PricedCartItemResult> {
        if (cartItems.isEmpty()) return emptyList()

        val productsById = productRepository.findAllById(cartItems.map { it.productId }).associateBy { it.id }
        val promotions = shopItemService.getEligiblePromotions(userId, now)
        val promotionProducts = promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(
            promotions.map { it.id },
        )
        val discountRates = calculateMaxDiscountRates(promotions, promotionProducts)
        val products = productsById.values.toList()
        val shopItems = shopItemService.createShopItems(userId, products, discountRates, now)
        val availabilities = shopItemService.getProductAvailabilities(userId, products, now)

        return cartItems.map { cartItem ->
            val product = productsById.getValue(cartItem.productId)
            val availability = availabilities.getValue(product.id)
            PricedCartItemResult(
                cartItem = cartItem,
                product = product,
                shopItem = shopItems.getValue(product.id),
                purchasePrice = calculatePurchasePrice(product.price, discountRates[product.id]),
                unavailableErrorCode = availability.getUnavailableErrorCode(cartItem.quantity),
                remainingPurchaseLimit = availability.remainingPurchaseLimit,
            )
        }
    }

    // 결제 정보는 선택됐고 구매 가능한 항목만 합친다
    private fun createCart(pricedCartItems: List<PricedCartItemResult>, balance: Int): CartResponse {
        val totalQuantity = pricedCartItems.sumOf { it.cartItem.quantity }
        val cartItemResponses = pricedCartItems.map {
            val maxQuantity = if (it.canPurchase) {
                minOf(
                    getMaxQuantityPerProduct(it.product, it.remainingPurchaseLimit),
                    shopProperties.maxCartQuantity - (totalQuantity - it.cartItem.quantity),
                )
            } else {
                0
            }
            CartItemResponse(
                shopItem = it.shopItem,
                quantity = it.cartItem.quantity,
                maxQuantity = maxQuantity,
                isSelected = it.cartItem.isSelected && it.canPurchase,
                canPurchase = it.canPurchase,
                purchasePrice = it.purchasePrice,
                unavailableReason = createUnavailableReason(it.unavailableErrorCode),
            )
        }

        val selectedItems = pricedCartItems.filter { it.cartItem.isSelected && it.canPurchase }
        val listPrice = selectedItems.sumOf { it.product.price * it.cartItem.quantity }
        val purchasePrice = selectedItems.sumOf { it.purchasePrice * it.cartItem.quantity }

        return CartResponse(
            cartItems = cartItemResponses,
            selectedItemCount = selectedItems.size,
            summary = CartSummaryResponse(
                quantity = selectedItems.sumOf { it.cartItem.quantity },
                listPrice = listPrice,
                discountPrice = listPrice - purchasePrice,
                purchasePrice = purchasePrice,
                balance = balance,
                balanceAfter = balance - purchasePrice,
            ),
        )
    }

    /**
     * 장바구니 항목 하나를 그리고 합산하는 데 필요한 값. 조회할 때마다 계산한다.
     *
     * @property purchasePrice 지금 결제할 1개 가격(할인 적용)
     * @property unavailableErrorCode 담긴 수량을 살 수 없는 사유. 수량 변경을 거절할 때 이 코드로 응답하고, 화면 문구도 이 코드로 정한다.
     *   null이면 구매 가능하다
     * @property remainingPurchaseLimit 1인 구매 한도까지 더 살 수 있는 개수. null이면 한도가 없다
     */
    private data class PricedCartItemResult(
        val cartItem: CartItem,
        val product: Product,
        val shopItem: ShopItemResponse,
        val purchasePrice: Int,
        val unavailableErrorCode: ShopErrorCode?,
        val remainingPurchaseLimit: Int?,
    ) {
        val canPurchase: Boolean get() = unavailableErrorCode == null
    }
}

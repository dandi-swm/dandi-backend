package com.dandi.nyummy.shop.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.pickTranslation
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.room.dto.GrantedRoomItem
import com.dandi.nyummy.shop.calculator.calculatePurchasePrice
import com.dandi.nyummy.shop.calculator.pickMaxDiscountPromotions
import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseResponse
import com.dandi.nyummy.shop.entity.CartItem
import com.dandi.nyummy.shop.entity.ItemPurchase
import com.dandi.nyummy.shop.entity.ItemPurchaseLine
import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ProductItem
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.enum.ItemPurchaseOutcome
import com.dandi.nyummy.shop.repository.CartItemRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRepository
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 상점 구매 트랜잭션. 실패하면 예외로 롤백되고, 실패 기록은 [ItemPurchaseService]가 롤백 뒤에 따로 남긴다.
 * 트랜잭션 안에서 예외를 잡으면 롤백이 표시된 채로 기록을 남기게 되므로 별도 빈으로 둔다.
 */
@Service
class ItemPurchaseTransactionService(
    private val clock: Clock,
    private val coinService: CoinService,
    private val shopItemService: ShopItemService,
    private val mailService: MailService,
    private val itemPurchaseAttemptService: ItemPurchaseAttemptService,
    private val cartItemRepository: CartItemRepository,
    private val productRepository: ProductRepository,
    private val productItemRepository: ProductItemRepository,
    private val promotionProductRepository: PromotionProductRepository,
    private val itemPurchaseRepository: ItemPurchaseRepository,
    private val itemPurchaseProductRepository: ItemPurchaseProductRepository,
    private val itemPurchaseLineRepository: ItemPurchaseLineRepository,
) {
    /**
     * 장바구니의 상품을 코인으로 사고, 산 아이템은 우편으로 보낸다.
     *
     * 코인 행 잠금이 첫 문장이다. 같은 사용자의 구매·장바구니 변경·우편 받기가 하나씩 처리되고,
     * REPEATABLE READ의 스냅샷이 잠금 뒤에 생겨 이후 조회가 앞 요청이 커밋한 데이터를 본다.
     * 그래서 같은 키로 동시에 와도 뒤 요청은 앞 구매를 찾아 처음 결과를 돌려받는다.
     *
     * 구매 상품은 상품 1개당 한 행, 구매 구성품은 구성품마다 한 행이다. 우편은 상품 종류당 한 통이고,
     * 그 안에 구매 구성품마다 아이템 한 줄이 들어가 환불할 때 하나씩 회수할 수 있다.
     *
     * @throws BusinessException [ShopErrorCode.IDEMPOTENCY_KEY_REUSED] 같은 키로 다른 상품·수량·가격을 보낸 경우
     * @throws BusinessException [ShopErrorCode.PRODUCT_NOT_FOUND] 없는 상품인 경우
     * @throws BusinessException [ShopErrorCode.CART_MISMATCH] 장바구니에 담긴 상품·수량과 다른 경우
     * @throws BusinessException [ShopErrorCode.PRODUCT_NOT_FOR_SALE] 판매 기간이 아닌 경우
     * @throws BusinessException [ShopErrorCode.ALREADY_OWNED] 이미 가진 방 배경인 경우
     * @throws BusinessException [ShopErrorCode.PURCHASE_LIMIT_EXCEEDED] 1인 구매 한도를 넘는 경우
     * @throws BusinessException [ShopErrorCode.PRICE_CHANGED] 화면에서 본 가격과 지금 가격이 다른 경우
     * @throws BusinessException [ShopErrorCode.INSUFFICIENT_BALANCE] 결제할 코인이 잔액보다 많은 경우
     */
    @Transactional
    fun createItemPurchase(
        userId: Long,
        idempotencyKey: String,
        request: CreateItemPurchaseRequest,
        appVersion: String?,
        language: Language,
    ): ItemPurchaseResponse {
        val coin = coinService.lockCoin(userId)
        val cartItems = cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)

        itemPurchaseRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)?.let { itemPurchase ->
            return replayItemPurchase(itemPurchase, request, appVersion, coin, cartItems)
        }

        val now = clock.instant()
        val productIds = request.products.map { it.productId }
        val productsById = productRepository.findAllById(productIds).associateBy { it.id }
        if (productsById.size != productIds.size) throw BusinessException(ShopErrorCode.PRODUCT_NOT_FOUND)
        validateCart(request, cartItems)

        val products = productsById.values.toList()
        val availabilities = shopItemService.getProductAvailabilities(userId, products, now)
        val promotionsByProductId = pickPromotions(userId, now)
        val requestedProducts = request.products.map { requested ->
            val product = productsById.getValue(requested.productId)
            val promotion = promotionsByProductId[product.id]
            availabilities.getValue(product.id).getUnavailableErrorCode(requested.quantity)
                ?.let { throw BusinessException(it) }
            val purchasePrice = calculatePurchasePrice(product.price, promotion?.discountRate)
            if (purchasePrice != requested.purchasePrice) throw BusinessException(ShopErrorCode.PRICE_CHANGED)
            RequestedProduct(product, promotion, requested.quantity, purchasePrice)
        }

        val totalPrice = requestedProducts.sumOf { it.purchasePrice * it.quantity }
        if (totalPrice > coin.balance) throw BusinessException(ShopErrorCode.INSUFFICIENT_BALANCE)

        // 거부는 여기까지 모두 끝내고 아래부터 저장한다. 구매 상품·우편·코인 내역(sourceId)이 구매 id를 가리키므로 구매를 먼저 저장한다.
        val itemPurchase = itemPurchaseRepository.save(ItemPurchase(userId, totalPrice, idempotencyKey))
        val productItems = productItemRepository.findByProductIdIn(productIds).groupBy { it.productId }
        requestedProducts.forEach { requestedProduct ->
            createItemPurchaseMail(
                userId,
                itemPurchase,
                requestedProduct,
                productItems.getValue(requestedProduct.product.id),
                language,
            )
        }

        // 0코인 결제는 잔액 변동이 없어 내역을 남기지 않는다
        if (totalPrice > 0) {
            coinService.decreaseBalance(coin, totalPrice, CoinHistoryCategory.ROOM_ITEM_PURCHASE, itemPurchase.id)
        }
        val (purchasedCartItems, remainingCartItems) = cartItems.partition { it.productId in productIds }
        cartItemRepository.deleteAllInBatch(purchasedCartItems)
        itemPurchaseAttemptService.createItemPurchaseAttempt(
            userId = userId,
            idempotencyKey = idempotencyKey,
            request = request,
            appVersion = appVersion,
            outcome = ItemPurchaseOutcome.SUCCEEDED,
            itemPurchaseId = itemPurchase.id,
        )

        return ItemPurchaseResponse(
            itemPurchaseId = itemPurchase.id,
            quantity = requestedProducts.sumOf { it.quantity },
            purchasePrice = totalPrice,
            balance = coin.balance,
            cartItemCount = remainingCartItems.size,
        )
    }

    // 같은 키의 구매가 있으면 요청이 같은지 보고 저장된 행으로 응답을 다시 만든다. 잔액과 장바구니 수는 지금 값이다.
    private fun replayItemPurchase(
        itemPurchase: ItemPurchase,
        request: CreateItemPurchaseRequest,
        appVersion: String?,
        coin: Coin,
        cartItems: List<CartItem>,
    ): ItemPurchaseResponse {
        val itemPurchaseProducts = itemPurchaseProductRepository.findByItemPurchaseId(itemPurchase.id)
        val purchased = itemPurchaseProducts
            .groupBy { it.productId }
            .mapValues { (_, rows) -> rows.size to rows.first().purchasePrice }
        val requested = request.products.associate { it.productId to (it.quantity to it.purchasePrice) }
        if (purchased != requested) throw BusinessException(ShopErrorCode.IDEMPOTENCY_KEY_REUSED)

        itemPurchaseAttemptService.createItemPurchaseAttempt(
            userId = itemPurchase.userId,
            idempotencyKey = itemPurchase.idempotencyKey,
            request = request,
            appVersion = appVersion,
            outcome = ItemPurchaseOutcome.REPLAYED,
            itemPurchaseId = itemPurchase.id,
        )

        return ItemPurchaseResponse(
            itemPurchaseId = itemPurchase.id,
            quantity = itemPurchaseProducts.size,
            purchasePrice = itemPurchase.purchasePrice,
            balance = coin.balance,
            cartItemCount = cartItems.size,
        )
    }

    // 요청한 상품이 그 수량 그대로 담겨 있어야 한다. 담겼지만 요청하지 않은 상품은 장바구니에 남는다.
    private fun validateCart(request: CreateItemPurchaseRequest, cartItems: List<CartItem>) {
        val cartQuantities = cartItems.associate { it.productId to it.quantity }
        if (request.products.any { cartQuantities[it.productId] != it.quantity }) {
            throw BusinessException(ShopErrorCode.CART_MISMATCH)
        }
    }

    private fun pickPromotions(userId: Long, now: Instant): Map<Long, Promotion> {
        val promotions = shopItemService.getEligiblePromotions(userId, now)
        val promotionProducts = promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(
            promotions.map { it.id },
        )
        return pickMaxDiscountPromotions(promotions, promotionProducts)
    }

    private fun createItemPurchaseMail(
        userId: Long,
        itemPurchase: ItemPurchase,
        requestedProduct: RequestedProduct,
        productItems: List<ProductItem>,
        language: Language,
    ) {
        val product = requestedProduct.product
        val itemPurchaseProducts = itemPurchaseProductRepository.saveAll(
            List(requestedProduct.quantity) {
                ItemPurchaseProduct(
                    itemPurchaseId = itemPurchase.id,
                    productId = product.id,
                    promotionId = requestedProduct.promotion?.id,
                    listPrice = product.price,
                    purchasePrice = requestedProduct.purchasePrice,
                )
            },
        )
        val lines = itemPurchaseLineRepository.saveAll(
            itemPurchaseProducts.flatMap { itemPurchaseProduct ->
                productItems.map { ItemPurchaseLine(itemPurchaseProduct.id, it.itemId, it.listPrice) }
            },
        )
        mailService.createItemPurchaseMail(
            userId = userId,
            // 우편 제목은 만들 때 저장하므로 구매 요청의 언어로 고른다. 이미 만든 우편은 그때 언어로 남는다
            title = product.nameTranslations.pickTranslation(language, product.name),
            representativeItemId = product.representativeItemId,
            items = lines.map { GrantedRoomItem(it.itemId, itemPurchaseLineId = it.id) },
            language = language,
        )
    }

    /**
     * 검증을 통과한 상품 하나.
     *
     * @property promotion 적용할 프로모션. 정가면 null이다
     * @property purchasePrice 1개 결제가
     */
    private data class RequestedProduct(
        val product: Product,
        val promotion: Promotion?,
        val quantity: Int,
        val purchasePrice: Int,
    )
}

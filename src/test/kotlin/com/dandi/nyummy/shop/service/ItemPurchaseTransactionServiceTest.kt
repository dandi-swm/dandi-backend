package com.dandi.nyummy.shop.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.mail.entity.Mail
import com.dandi.nyummy.mail.enum.MailCategory
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.room.dto.GrantedRoomItem
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseProductRequest
import com.dandi.nyummy.shop.dto.ProductAvailabilityResult
import com.dandi.nyummy.shop.entity.CartItem
import com.dandi.nyummy.shop.entity.ItemPurchase
import com.dandi.nyummy.shop.entity.ItemPurchaseLine
import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ProductItem
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.enum.ItemPurchaseOutcome
import com.dandi.nyummy.shop.enum.PromotionCategory
import com.dandi.nyummy.shop.repository.CartItemRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRepository
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ItemPurchaseTransactionServiceTest {

    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val userId = 1L
    private val idempotencyKey = "key-1"
    private val itemPurchaseId = 500L

    private val coinService = mockk<CoinService>()
    private val shopItemService = mockk<ShopItemService>()
    private val mailService = mockk<MailService>()
    private val itemPurchaseAttemptService = mockk<ItemPurchaseAttemptService>()
    private val cartItemRepository = mockk<CartItemRepository>()
    private val productRepository = mockk<ProductRepository>()
    private val productItemRepository = mockk<ProductItemRepository>()
    private val promotionProductRepository = mockk<PromotionProductRepository>()
    private val itemPurchaseRepository = mockk<ItemPurchaseRepository>()
    private val itemPurchaseProductRepository = mockk<ItemPurchaseProductRepository>()
    private val itemPurchaseLineRepository = mockk<ItemPurchaseLineRepository>()

    private val service = ItemPurchaseTransactionService(
        clock = Clock.fixed(now, ZoneOffset.UTC),
        coinService = coinService,
        shopItemService = shopItemService,
        mailService = mailService,
        itemPurchaseAttemptService = itemPurchaseAttemptService,
        cartItemRepository = cartItemRepository,
        productRepository = productRepository,
        productItemRepository = productItemRepository,
        promotionProductRepository = promotionProductRepository,
        itemPurchaseRepository = itemPurchaseRepository,
        itemPurchaseProductRepository = itemPurchaseProductRepository,
        itemPurchaseLineRepository = itemPurchaseLineRepository,
    )

    // 테스트마다 채우는 DB 상태
    private val coin = Coin(userId).also { it.balance = 10000 }
    private var products = listOf<Product>()
    private var productItems = listOf<ProductItem>()
    private var cartItems = listOf<CartItem>()
    private var promotions = listOf<Promotion>()
    private var promotionProducts = listOf<PromotionProduct>()
    private var availabilities = mapOf<Long, ProductAvailabilityResult>()

    // 저장된 행
    private val savedItemPurchaseProducts = mutableListOf<ItemPurchaseProduct>()
    private val savedLines = mutableListOf<ItemPurchaseLine>()

    init {
        every { coinService.lockCoin(userId) } returns coin
        every { coinService.decreaseBalance(coin, any(), any(), any()) } answers {
            coin.decreaseBalance(secondArg())
            coin.balance
        }
        every { cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId) } answers { cartItems }
        every { cartItemRepository.deleteAllInBatch(any<Iterable<CartItem>>()) } just runs
        every { itemPurchaseRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey) } returns null
        every { itemPurchaseRepository.save(any()) } answers { firstArg<ItemPurchase>().withId(itemPurchaseId) }
        every { productRepository.findAllById(any()) } answers {
            val productIds = firstArg<Iterable<Long>>().toSet()
            products.filter { it.id in productIds }
        }
        every { productItemRepository.findByProductIdIn(any()) } answers {
            val productIds = firstArg<Collection<Long>>()
            productItems.filter { it.productId in productIds }
        }
        every { shopItemService.getEligiblePromotions(userId, now) } answers { promotions }
        every { shopItemService.getProductAvailabilities(userId, any(), now) } answers {
            secondArg<List<Product>>().associate {
                it.id to (availabilities[it.id] ?: ProductAvailabilityResult(null, null))
            }
        }
        every { promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(any()) } answers {
            promotionProducts
        }
        every { itemPurchaseProductRepository.saveAll(any<List<ItemPurchaseProduct>>()) } answers {
            firstArg<List<ItemPurchaseProduct>>().map { row ->
                savedItemPurchaseProducts += row
                row.withId(1000L + savedItemPurchaseProducts.size)
            }
        }
        every { itemPurchaseLineRepository.saveAll(any<List<ItemPurchaseLine>>()) } answers {
            firstArg<List<ItemPurchaseLine>>().map { row ->
                savedLines += row
                row.withId(2000L + savedLines.size)
            }
        }
        every { mailService.createItemPurchaseMail(any(), any(), any(), any(), any()) } returns mockk<Mail>()
        every {
            itemPurchaseAttemptService.createItemPurchaseAttempt(any(), any(), any(), any(), any(), any())
        } just runs
    }

    private fun <T : Any> T.withId(id: Long): T = also { ReflectionTestUtils.setField(it, "id", id) }

    private fun product(id: Long, price: Int = 1000) = Product(
        category = RoomItemCategory.BED,
        name = "상품 $id",
        representativeItemId = id * 10,
        price = price,
        isLimited = false,
        saleStartsAt = now.minus(Duration.ofDays(30)),
    ).withId(id)

    private fun single(product: Product) = ProductItem(product.id, product.representativeItemId, product.price)

    private fun cartItem(productId: Long, quantity: Int) = CartItem(userId, productId, quantity).withId(productId * 100)

    private fun promotion(id: Long, discountRate: Int) = Promotion(
        name = "프로모션 $id",
        category = PromotionCategory.GENERAL,
        discountRate = discountRate,
        startsAt = now.minus(Duration.ofDays(1)),
    ).withId(id)

    private fun givenSingle(product: Product, quantity: Int = 1) {
        products = products + product
        productItems = productItems + single(product)
        cartItems = cartItems + cartItem(product.id, quantity)
    }

    private fun request(vararg products: Triple<Long, Int, Int>) = CreateItemPurchaseRequest(
        products.map { (productId, quantity, purchasePrice) ->
            ItemPurchaseProductRequest(productId, quantity, purchasePrice)
        },
    )

    private fun purchase(request: CreateItemPurchaseRequest) =
        service.createItemPurchase(userId, idempotencyKey, request, appVersion = "1.0.0", language = Language.KO)

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun assertNothingSaved() {
        verify(exactly = 0) { itemPurchaseRepository.save(any()) }
        verify(exactly = 0) { coinService.decreaseBalance(any(), any(), any(), any()) }
        verify(exactly = 0) { mailService.createItemPurchaseMail(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { cartItemRepository.deleteAllInBatch(any<Iterable<CartItem>>()) }
    }

    // 성공

    @Test
    fun `코인을 먼저 잠그고 차감한 뒤 산 상품만 장바구니에서 빼고 성공을 기록한다`() {
        givenSingle(product(101, price = 1000), quantity = 2)
        givenSingle(product(102, price = 500))
        val notRequested = cartItem(103, quantity = 1)
        cartItems = cartItems + notRequested
        val purchasedCartItems = slot<Iterable<CartItem>>()
        every { cartItemRepository.deleteAllInBatch(capture(purchasedCartItems)) } just runs

        val response = purchase(request(Triple(101L, 2, 1000), Triple(102L, 1, 500)))

        assertEquals(itemPurchaseId, response.itemPurchaseId)
        assertEquals(3, response.quantity)
        assertEquals(2500, response.purchasePrice)
        assertEquals(7500, response.balance)
        assertEquals(1, response.cartItemCount)
        assertEquals(listOf(101L, 102L), purchasedCartItems.captured.map { it.productId })
        verifyOrder {
            coinService.lockCoin(userId)
            itemPurchaseRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
            coinService.decreaseBalance(coin, 2500, CoinHistoryCategory.ROOM_ITEM_PURCHASE, itemPurchaseId)
            itemPurchaseAttemptService.createItemPurchaseAttempt(
                userId,
                idempotencyKey,
                any(),
                "1.0.0",
                ItemPurchaseOutcome.SUCCEEDED,
                itemPurchaseId,
            )
        }
    }

    @Test
    fun `같은 상품을 여러 개 사면 구매 상품은 1개당 한 행이고 우편은 종류당 한 통이다`() {
        givenSingle(product(101), quantity = 3)

        purchase(request(Triple(101L, 3, 1000)))

        assertEquals(3, savedItemPurchaseProducts.size)
        assertEquals(3, savedLines.size)
        verify(exactly = 1) {
            mailService.createItemPurchaseMail(
                userId = userId,
                title = "상품 101",
                language = Language.KO,
                representativeItemId = 1010L,
                items = listOf(
                    GrantedRoomItem(1010L, itemPurchaseLineId = 2001L),
                    GrantedRoomItem(1010L, itemPurchaseLineId = 2002L),
                    GrantedRoomItem(1010L, itemPurchaseLineId = 2003L),
                ),
            )
        }
    }

    @Test
    fun `세트는 상품 1개마다 구성품을 줄로 저장하고 구성품 정가를 남긴다`() {
        val set = product(101, price = 1500)
        products = listOf(set)
        productItems = listOf(ProductItem(101, 1L, 1000), ProductItem(101, 2L, 800))
        cartItems = listOf(cartItem(101, quantity = 2))

        purchase(request(Triple(101L, 2, 1500)))

        assertEquals(2, savedItemPurchaseProducts.size)
        assertEquals(listOf(1001L, 1001L, 1002L, 1002L), savedLines.map { it.itemPurchaseProductId })
        assertEquals(listOf(1000, 800, 1000, 800), savedLines.map { it.listPrice })
        verify(exactly = 1) { mailService.createItemPurchaseMail(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `할인을 적용하면 할인율이 가장 큰 프로모션과 정가·결제가를 저장한다`() {
        givenSingle(product(101, price = 1000))
        promotions = listOf(promotion(1, discountRate = 10), promotion(2, discountRate = 30))
        promotionProducts = listOf(PromotionProduct(1, 101, 0), PromotionProduct(2, 101, 0))

        val response = purchase(request(Triple(101L, 1, 700)))

        val row = savedItemPurchaseProducts.single()
        assertEquals(2L, row.promotionId)
        assertEquals(1000, row.listPrice)
        assertEquals(700, row.purchasePrice)
        assertEquals(700, response.purchasePrice)
    }

    @Test
    fun `0코인 결제는 구매를 만들지만 잔액을 건드리지 않는다`() {
        givenSingle(product(101, price = 0))

        val response = purchase(request(Triple(101L, 1, 0)))

        assertEquals(0, response.purchasePrice)
        assertEquals(10000, response.balance)
        verify(exactly = 1) { itemPurchaseRepository.save(any()) }
        verify(exactly = 0) { coinService.decreaseBalance(any(), any(), any(), any()) }
    }

    // 같은 Idempotency-Key

    private fun givenPurchased(purchasePrice: Int = 1000, quantity: Int = 2) {
        every { itemPurchaseRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey) } returns
            ItemPurchase(userId, purchasePrice * quantity, idempotencyKey).withId(itemPurchaseId)
        every { itemPurchaseProductRepository.findByItemPurchaseId(itemPurchaseId) } returns List(quantity) {
            ItemPurchaseProduct(itemPurchaseId, 101, null, 1000, purchasePrice)
        }
    }

    @Test
    fun `같은 키로 같은 구매를 다시 보내면 저장 없이 처음 결과를 돌려주고 기록을 남긴다`() {
        givenPurchased()
        coin.balance = 8000

        val response = purchase(request(Triple(101L, 2, 1000)))

        assertEquals(itemPurchaseId, response.itemPurchaseId)
        assertEquals(2, response.quantity)
        assertEquals(2000, response.purchasePrice)
        assertEquals(8000, response.balance)
        assertNothingSaved()
        verify {
            itemPurchaseAttemptService.createItemPurchaseAttempt(
                userId,
                idempotencyKey,
                any(),
                "1.0.0",
                ItemPurchaseOutcome.REPLAYED,
                itemPurchaseId,
            )
        }
    }

    @Test
    fun `같은 키로 상품·수량·가격이 다른 구매를 보내면 거부한다`() {
        givenPurchased()

        assertBusinessException(ShopErrorCode.IDEMPOTENCY_KEY_REUSED) { purchase(request(Triple(102L, 2, 1000))) }
        assertBusinessException(ShopErrorCode.IDEMPOTENCY_KEY_REUSED) { purchase(request(Triple(101L, 1, 1000))) }
        assertBusinessException(ShopErrorCode.IDEMPOTENCY_KEY_REUSED) { purchase(request(Triple(101L, 2, 900))) }
        assertNothingSaved()
    }

    // 거부

    @Test
    fun `없는 상품이면 거부한다`() {
        givenSingle(product(101))

        assertBusinessException(ShopErrorCode.PRODUCT_NOT_FOUND) {
            purchase(request(Triple(101L, 1, 1000), Triple(999L, 1, 1000)))
        }
        assertNothingSaved()
    }

    @Test
    fun `장바구니에 없거나 담긴 수량과 다르면 거부한다`() {
        givenSingle(product(101), quantity = 2)
        products = products + product(102)

        assertBusinessException(ShopErrorCode.CART_MISMATCH) { purchase(request(Triple(101L, 1, 1000))) }
        assertBusinessException(ShopErrorCode.CART_MISMATCH) { purchase(request(Triple(102L, 1, 1000))) }
        assertNothingSaved()
    }

    @Test
    fun `살 수 없는 상품이면 그 사유로 거부한다`() {
        givenSingle(product(101))

        listOf(
            ShopErrorCode.PRODUCT_NOT_FOR_SALE,
            ShopErrorCode.ALREADY_OWNED,
            ShopErrorCode.PURCHASE_LIMIT_EXCEEDED,
        ).forEach { errorCode ->
            availabilities = mapOf(101L to ProductAvailabilityResult(errorCode, null))

            assertBusinessException(errorCode) { purchase(request(Triple(101L, 1, 1000))) }
        }
        assertNothingSaved()
    }

    @Test
    fun `남은 1인 구매 한도보다 많이 사면 거부한다`() {
        givenSingle(product(101), quantity = 2)
        availabilities = mapOf(101L to ProductAvailabilityResult(null, remainingPurchaseLimit = 1))

        assertBusinessException(ShopErrorCode.PURCHASE_LIMIT_EXCEEDED) { purchase(request(Triple(101L, 2, 1000))) }
        assertNothingSaved()
    }

    @Test
    fun `화면에서 본 가격이 지금 가격과 다르면 거부한다`() {
        givenSingle(product(101, price = 1000))

        assertBusinessException(ShopErrorCode.PRICE_CHANGED) { purchase(request(Triple(101L, 1, 900))) }
        assertNothingSaved()
    }

    @Test
    fun `결제할 코인이 잔액보다 많으면 거부하고 잔액과 같으면 산다`() {
        givenSingle(product(101, price = 10000))
        coin.balance = 9999

        assertBusinessException(ShopErrorCode.INSUFFICIENT_BALANCE) { purchase(request(Triple(101L, 1, 10000))) }
        assertNothingSaved()

        coin.balance = 10000
        assertEquals(0, purchase(request(Triple(101L, 1, 10000))).balance)
    }
}

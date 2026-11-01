package com.dandi.nyummy.shop.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.repository.CoinRepository
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.room.dto.OwnedRoomItemCountResult
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.dto.CartResponse
import com.dandi.nyummy.shop.dto.CreateCartItemRequest
import com.dandi.nyummy.shop.dto.UpdateCartItemQuantityRequest
import com.dandi.nyummy.shop.dto.UpdateCartSelectionRequest
import com.dandi.nyummy.shop.entity.CartItem
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ProductItem
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.enum.PromotionCategory
import com.dandi.nyummy.shop.repository.CartItemRepository
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import com.dandi.nyummy.shop.repository.PromotionRepository
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CartServiceTest {

    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val userId = 1L

    private val userRepository = mockk<UserRepository>()
    private val promotionRepository = mockk<PromotionRepository>()
    private val promotionProductRepository = mockk<PromotionProductRepository>()
    private val productRepository = mockk<ProductRepository>()
    private val productItemRepository = mockk<ProductItemRepository>()
    private val ownedRoomItemRepository = mockk<OwnedRoomItemRepository>()
    private val coinRepository = mockk<CoinRepository>()
    private val coinService = mockk<CoinService>()
    private val cartItemRepository = mockk<CartItemRepository>()

    // 상품당 10개, 장바구니 전체 30개
    private val shopProperties = ShopProperties()

    private val cartService = CartService(
        clock = Clock.fixed(now, ZoneOffset.UTC),
        shopItemService = ShopItemService(
            userRepository = userRepository,
            promotionRepository = promotionRepository,
            productItemRepository = productItemRepository,
            ownedRoomItemRepository = ownedRoomItemRepository,
            shopProperties = shopProperties,
        ),
        coinService = coinService,
        shopProperties = shopProperties,
        coinRepository = coinRepository,
        cartItemRepository = cartItemRepository,
        productRepository = productRepository,
        promotionProductRepository = promotionProductRepository,
        ownedRoomItemRepository = ownedRoomItemRepository,
    )

    // 테스트마다 채우는 DB 상태
    private var products = listOf<Product>()
    private var promotions = listOf<Promotion>()
    private var promotionProducts = listOf<PromotionProduct>()
    private var cartItems = listOf<CartItem>()

    // 보유한 아이템 id
    private var ownedItemIds = setOf<Long>()

    init {
        val user = User(provider = AuthProvider.EMAIL, email = "cat@test.com")
            .also { it.createdAt = now.minus(Duration.ofDays(30)) }
        every { userRepository.findByIdOrNull(userId) } returns user
        every { promotionRepository.findActivePromotions(now) } answers { promotions }
        every { promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(any()) } answers {
            val promotionIds = firstArg<Collection<Long>>()
            promotionProducts.filter { it.promotionId in promotionIds }.sortedBy { it.displayOrder }
        }
        every { productRepository.findById(any()) } answers {
            Optional.ofNullable(products.find { it.id == firstArg<Long>() })
        }
        every { productRepository.findAllById(any()) } answers {
            val productIds = firstArg<Iterable<Long>>().toSet()
            products.filter { it.id in productIds }
        }
        // 모두 단품이라 구성품은 대표 아이템 하나다
        every { productItemRepository.findByProductIdIn(any()) } answers {
            val productIds = firstArg<Collection<Long>>()
            products.filter { it.id in productIds }.map { ProductItem(it.id, it.representativeItemId, it.price) }
        }
        every { ownedRoomItemRepository.findOwnedRoomItemCounts(userId, any()) } answers {
            secondArg<List<Long>>().filter { it in ownedItemIds }.map { OwnedRoomItemCountResult(it, 1) }
        }
        every { coinRepository.findCoinWithLockByUserId(userId) } returns Coin(userId).also { it.balance = 1000 }
        every { coinService.getBalance(userId) } returns 1000
        every { cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId) } answers { cartItems }
        every { cartItemRepository.save(any<CartItem>()) } answers { firstArg() }
        every { cartItemRepository.deleteAll(any<Iterable<CartItem>>()) } just Runs
    }

    private fun product(
        id: Long,
        price: Int = 1000,
        category: RoomItemCategory = RoomItemCategory.BED,
        saleEndsAt: Instant? = null,
    ) = Product(
        category = category,
        name = "상품 $id",
        representativeItemId = id * 10,
        price = price,
        isLimited = false,
        saleStartsAt = now.minus(Duration.ofDays(30)),
        saleEndsAt = saleEndsAt,
    ).also { ReflectionTestUtils.setField(it, "id", id) }

    private fun cartItem(product: Product, quantity: Int = 1, isSelected: Boolean = true) =
        CartItem(userId = userId, productId = product.id, quantity = quantity).also {
            it.updateIsSelected(isSelected)
            ReflectionTestUtils.setField(it, "id", product.id * 100)
        }

    private fun promotion(id: Long, discountRate: Int) = Promotion(
        name = "프로모션 $id",
        category = PromotionCategory.GENERAL,
        discountRate = discountRate,
        startsAt = now.minus(Duration.ofDays(1)),
        endsAt = null,
    ).also { ReflectionTestUtils.setField(it, "id", id) }

    private fun CartResponse.itemOf(product: Product) = cartItems.single { it.shopItem.productId == product.id }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `조회하면 선택됐고 구매 가능한 항목만 결제 정보에 합친다`() {
        val bed = product(101, price = 1000)
        val rug = product(102, price = 500, category = RoomItemCategory.RUG)
        val ended = product(103, price = 300, saleEndsAt = now.minus(Duration.ofDays(1)))
        products = listOf(bed, rug, ended)
        promotions = listOf(promotion(2, discountRate = 30))
        promotionProducts = listOf(PromotionProduct(2, 101, 0))
        // 판매가 끝난 103은 선택돼 있어도 합치지 않는다
        cartItems = listOf(cartItem(bed, quantity = 2), cartItem(rug, isSelected = false), cartItem(ended))

        val cart = cartService.getCart(userId)

        assertEquals(1, cart.selectedItemCount)
        assertEquals(2, cart.summary.quantity)
        assertEquals(2000, cart.summary.listPrice)
        // 30% 할인 700 × 2
        assertEquals(1400, cart.summary.purchasePrice)
        assertEquals(600, cart.summary.discountPrice)
        assertEquals(1000, cart.summary.balance)
        assertEquals(-400, cart.summary.balanceAfter)

        assertEquals(700, cart.itemOf(bed).purchasePrice)
        assertEquals(false, cart.itemOf(ended).canPurchase)
        assertEquals(false, cart.itemOf(ended).isSelected)
        assertEquals("판매가 끝났어요", cart.itemOf(ended).unavailableReason)
    }

    @Test
    fun `담을 수 있는 수량은 상품당 상한과 장바구니에 남은 수량 중 작은 값이고 구매 불가 항목은 0이다`() {
        val bed = product(101)
        val rug = product(102, category = RoomItemCategory.RUG)
        val background = product(103, category = RoomItemCategory.BACKGROUND)
        val ownedBackground = product(104, category = RoomItemCategory.BACKGROUND)
        products = listOf(bed, rug, background, ownedBackground)
        ownedItemIds = setOf(ownedBackground.representativeItemId)
        // 전체 30개 중 27개가 차 있다
        cartItems = listOf(
            cartItem(bed, quantity = 2),
            cartItem(rug, quantity = 23),
            cartItem(background),
            cartItem(ownedBackground),
        )

        val cart = cartService.getCart(userId)

        // 30 − (27 − 2) = 5 < 상품당 10
        assertEquals(5, cart.itemOf(bed).maxQuantity)
        // 방 배경은 1개
        assertEquals(1, cart.itemOf(background).maxQuantity)
        assertEquals(0, cart.itemOf(ownedBackground).maxQuantity)
        assertEquals("이미 가진 방 배경이에요", cart.itemOf(ownedBackground).unavailableReason)
    }

    @Test
    fun `이미 담긴 상품을 담으면 코인 행을 잠근 뒤 수량을 더하고 선택 상태로 만든다`() {
        val bed = product(101)
        products = listOf(bed)
        val existing = cartItem(bed, quantity = 2, isSelected = false)
        cartItems = listOf(existing)

        val response = cartService.createCartItem(userId, CreateCartItemRequest(productId = 101, quantity = 3))

        assertEquals(5, response.quantity)
        assertEquals(1, response.cartItemCount)
        assertEquals(5, existing.quantity)
        assertTrue(existing.isSelected)
        verify(exactly = 0) { cartItemRepository.save(any<CartItem>()) }
        // 잠금이 첫 문장이어야 그 뒤 조회가 앞 요청이 커밋한 행을 본다
        verifyOrder {
            coinRepository.findCoinWithLockByUserId(userId)
            productRepository.findById(101)
            cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)
        }
    }

    @Test
    fun `처음 담는 상품은 새 항목으로 저장하고 상품 종류 수가 하나 는다`() {
        val bed = product(101)
        val rug = product(102, category = RoomItemCategory.RUG)
        products = listOf(bed, rug)
        cartItems = listOf(cartItem(rug))

        val response = cartService.createCartItem(userId, CreateCartItemRequest(productId = 101, quantity = 2))

        assertEquals(2, response.quantity)
        assertEquals(2, response.cartItemCount)
        verify {
            cartItemRepository.save(match<CartItem> { it.userId == userId && it.productId == 101L && it.quantity == 2 })
        }
    }

    @Test
    fun `없거나 판매가 끝났거나 이미 가진 방 배경이면 담지 않는다`() {
        val ended = product(101, saleEndsAt = now.minus(Duration.ofDays(1)))
        val ownedBackground = product(102, category = RoomItemCategory.BACKGROUND)
        products = listOf(ended, ownedBackground)
        ownedItemIds = setOf(ownedBackground.representativeItemId)

        assertBusinessException(ShopErrorCode.PRODUCT_NOT_FOUND) {
            cartService.createCartItem(userId, CreateCartItemRequest(productId = 999, quantity = 1))
        }
        assertBusinessException(ShopErrorCode.PRODUCT_NOT_FOR_SALE) {
            cartService.createCartItem(userId, CreateCartItemRequest(productId = 101, quantity = 1))
        }
        assertBusinessException(ShopErrorCode.ALREADY_OWNED) {
            cartService.createCartItem(userId, CreateCartItemRequest(productId = 102, quantity = 1))
        }
        verify(exactly = 0) { cartItemRepository.save(any<CartItem>()) }
    }

    @Test
    fun `상품당 상한이나 장바구니 전체 상한을 넘기면 담지 않는다`() {
        val bed = product(101)
        val rug = product(102, category = RoomItemCategory.RUG)
        val background = product(103, category = RoomItemCategory.BACKGROUND)
        val lamp = product(104, category = RoomItemCategory.ETC)
        products = listOf(bed, rug, background, lamp)

        // 상품당 10개
        cartItems = listOf(cartItem(bed, quantity = 9))
        assertBusinessException(ShopErrorCode.CART_QUANTITY_EXCEEDED) {
            cartService.createCartItem(userId, CreateCartItemRequest(productId = 101, quantity = 2))
        }
        // 방 배경은 1개
        assertBusinessException(ShopErrorCode.CART_QUANTITY_EXCEEDED) {
            cartService.createCartItem(userId, CreateCartItemRequest(productId = 103, quantity = 2))
        }
        // 전체 30개. 30개가 차 있으면 상품당 상한 안인 새 상품 1개도 담지 못한다
        cartItems = listOf(cartItem(bed, quantity = 10), cartItem(rug, quantity = 10), cartItem(lamp, quantity = 10))
        assertBusinessException(ShopErrorCode.CART_FULL) {
            cartService.createCartItem(userId, CreateCartItemRequest(productId = 103, quantity = 1))
        }
        verify(exactly = 0) { cartItemRepository.save(any<CartItem>()) }
    }

    @Test
    fun `수량을 바꾸면 그 수량으로 결제 정보를 다시 합친다`() {
        val bed = product(101, price = 1000)
        products = listOf(bed)
        val existing = cartItem(bed, quantity = 1)
        cartItems = listOf(existing)

        val cart = cartService.updateCartItemQuantity(userId, 101, UpdateCartItemQuantityRequest(quantity = 4))

        assertEquals(4, existing.quantity)
        assertEquals(4, cart.itemOf(bed).quantity)
        assertEquals(4000, cart.summary.purchasePrice)
    }

    @Test
    fun `구매할 수 없는 항목의 수량을 바꾸면 그 사유로 거부한다`() {
        val ended = product(101, saleEndsAt = now.minus(Duration.ofDays(1)))
        val ownedBackground = product(102, category = RoomItemCategory.BACKGROUND)
        products = listOf(ended, ownedBackground)
        ownedItemIds = setOf(ownedBackground.representativeItemId)
        cartItems = listOf(cartItem(ended), cartItem(ownedBackground))

        assertBusinessException(ShopErrorCode.CART_ITEM_NOT_FOUND) {
            cartService.updateCartItemQuantity(userId, 999, UpdateCartItemQuantityRequest(quantity = 1))
        }
        assertBusinessException(ShopErrorCode.PRODUCT_NOT_FOR_SALE) {
            cartService.updateCartItemQuantity(userId, 101, UpdateCartItemQuantityRequest(quantity = 1))
        }
        assertBusinessException(ShopErrorCode.ALREADY_OWNED) {
            cartService.updateCartItemQuantity(userId, 102, UpdateCartItemQuantityRequest(quantity = 1))
        }
    }

    @Test
    fun `선택은 요청 목록으로 덮어쓰고 목록에 없거나 구매할 수 없는 항목은 해제한다`() {
        val bed = product(101)
        val rug = product(102, category = RoomItemCategory.RUG)
        val ended = product(103, saleEndsAt = now.minus(Duration.ofDays(1)))
        products = listOf(bed, rug, ended)
        val selectedBed = cartItem(bed, isSelected = true)
        val unselectedRug = cartItem(rug, isSelected = false)
        val endedItem = cartItem(ended, isSelected = false)
        cartItems = listOf(selectedBed, unselectedRug, endedItem)

        // 장바구니에 없는 999는 무시한다
        val cart = cartService.updateCartSelection(
            userId,
            UpdateCartSelectionRequest(productIds = listOf(102, 103, 999)),
        )

        assertEquals(false, selectedBed.isSelected)
        assertEquals(true, unselectedRug.isSelected)
        assertEquals(false, endedItem.isSelected)
        assertEquals(1, cart.selectedItemCount)
    }

    @Test
    fun `빼면 그 항목을 지우고 남은 항목으로 장바구니를 만든다`() {
        val bed = product(101)
        val rug = product(102, category = RoomItemCategory.RUG)
        products = listOf(bed, rug)
        val bedItem = cartItem(bed)
        cartItems = listOf(bedItem, cartItem(rug))

        // 장바구니에 없는 999는 무시한다
        val cart = cartService.deleteCartItems(userId, listOf(101, 999))

        verify { cartItemRepository.deleteAll(listOf(bedItem)) }
        assertEquals(listOf(102L), cart.cartItems.map { it.shopItem.productId })
        assertNull(cart.cartItems.find { it.shopItem.productId == 101L })
    }
}

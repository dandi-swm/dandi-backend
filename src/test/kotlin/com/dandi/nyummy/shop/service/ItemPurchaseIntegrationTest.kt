package com.dandi.nyummy.shop.service

import com.dandi.nyummy.admin.entity.Admin
import com.dandi.nyummy.admin.repository.AdminRepository
import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.repository.CoinRepository
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.RoomErrorCode
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.item.entity.Item
import com.dandi.nyummy.item.repository.ItemRepository
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.room.dto.EquippedRoomItemRequest
import com.dandi.nyummy.room.dto.UpdateEquippedRoomItemsRequest
import com.dandi.nyummy.room.entity.RoomItem
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.repository.RoomItemRepository
import com.dandi.nyummy.room.service.RoomService
import com.dandi.nyummy.shop.dto.CreateCartItemRequest
import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseProductRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseResponse
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 구매를 실제 MySQL에서 처음부터 끝까지 돌린다. 쿼리, 롤백 뒤의 거부 기록, 코인 잠금 위의 동시 요청은 mock으로 확인할 수 없다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ItemPurchaseIntegrationTest @Autowired constructor(
    private val itemPurchaseService: ItemPurchaseService,
    private val cartService: CartService,
    private val mailService: MailService,
    private val coinService: CoinService,
    private val shopItemService: ShopItemService,
    private val roomService: RoomService,
    private val userRepository: UserRepository,
    private val adminRepository: AdminRepository,
    private val coinRepository: CoinRepository,
    private val itemRepository: ItemRepository,
    private val roomItemRepository: RoomItemRepository,
    private val productRepository: ProductRepository,
    private val productItemRepository: ProductItemRepository,
    private val promotionRepository: PromotionRepository,
    private val promotionProductRepository: PromotionProductRepository,
    private val cartItemRepository: CartItemRepository,
    private val transactionTemplate: TransactionTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")

        private const val TIMEOUT_SECONDS = 30L
        private const val REQUEST_COUNT = 8
        private const val STARTING_BALANCE = 100_000
    }

    @Test
    fun `사면 코인이 빠지고 우편을 받으면 구매 구성품이 이어진 보유 아이템이 생긴다`() {
        val userId = createUser()
        val set = createProduct(price = 1500, itemCount = 2)
        val single = createProduct(price = 500)
        val notRequested = createProduct(price = 300)
        createCartItem(userId, set, quantity = 2)
        createCartItem(userId, single, quantity = 1)
        createCartItem(userId, notRequested, quantity = 1)

        val response = purchase(userId, listOf(set to 2, single to 1))

        assertEquals(3, response.quantity)
        assertEquals(3500, response.purchasePrice)
        assertEquals(STARTING_BALANCE - 3500, response.balance)
        assertEquals(1, response.cartItemCount)
        assertEquals(STARTING_BALANCE - 3500, getBalance(userId))
        assertEquals(
            listOf(notRequested.id),
            cartItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).map {
                it.productId
            },
        )

        // 상품 1개당 한 행(3), 구성품마다 한 행(세트 2개 × 2 + 단품 1 = 5), 우편은 종류당 한 통(2)
        assertEquals(
            3,
            queryForInt(
                "select count(*) from item_purchase_products where item_purchase_id = ?",
                response.itemPurchaseId,
            ),
        )
        assertEquals(
            5,
            queryForInt(
                """
                select count(*) from item_purchase_lines l
                    join item_purchase_products p on p.id = l.item_purchase_product_id
                where p.item_purchase_id = ?
                """,
                response.itemPurchaseId,
            ),
        )
        assertEquals(2, queryForInt("select count(*) from mails where user_id = ? and category = 'ITEM'", userId))
        assertEquals(
            5,
            queryForInt(
                """
                select count(*) from mail_items mi join mails m on m.id = mi.mail_id
                where m.user_id = ? and mi.item_purchase_line_id is not null and mi.quantity = 1
                """,
                userId,
            ),
        )
        assertEquals(
            -3500,
            queryForInt(
                "select quantity from coin_histories where user_id = ? and category = 'ROOM_ITEM_PURCHASE'",
                userId,
            ),
        )
        assertEquals(1, countAttempts(userId, "SUCCEEDED"))
        val coinHistory = coinService.getCoinHistories(
            userId,
            cursor = null,
            size = 10,
            language = Language.KO,
        ).coinHistories.single()
        assertEquals("상품 외 2개 구매", coinHistory.title)
        assertEquals(-3500, coinHistory.quantity)
        assertEquals(STARTING_BALANCE - 3500, coinHistory.balanceAfter)

        mailService.claimMails(userId)

        assertEquals(
            5,
            queryForInt(
                "select count(distinct item_purchase_line_id) from owned_room_items where user_id = ?",
                userId,
            ),
        )
        assertEquals(STARTING_BALANCE - 3500, getBalance(userId))
    }

    @Test
    fun `같은 키로 동시에 보내도 한 번만 사고 모두 같은 구매를 받는다`() {
        val userId = createUser()
        val product = createProduct(price = 1000)
        createCartItem(userId, product, quantity = 1)
        val idempotencyKey = UUID.randomUUID().toString()

        val results = runConcurrently(REQUEST_COUNT) { purchase(userId, listOf(product to 1), idempotencyKey) }

        val itemPurchaseIds = results.map { it.getOrThrow().itemPurchaseId }.toSet()
        assertEquals(1, itemPurchaseIds.size, "같은 키가 다른 구매를 돌려줬습니다: $itemPurchaseIds")
        assertEquals(STARTING_BALANCE - 1000, getBalance(userId))
        assertEquals(1, queryForInt("select count(*) from item_purchases where user_id = ?", userId))
        assertEquals(1, countAttempts(userId, "SUCCEEDED"))
        assertEquals(REQUEST_COUNT - 1, countAttempts(userId, "REPLAYED"))
    }

    @Test
    fun `다른 키로 동시에 보내면 하나만 사고 나머지는 장바구니 불일치로 거부한다`() {
        val userId = createUser()
        val product = createProduct(price = 1000)
        createCartItem(userId, product, quantity = 1)

        val results = runConcurrently(REQUEST_COUNT) { purchase(userId, listOf(product to 1)) }

        assertEquals(1, results.count { it.isSuccess })
        results.mapNotNull { it.exceptionOrNull() }.forEach { failure ->
            assertEquals(ShopErrorCode.CART_MISMATCH, (failure as BusinessException).errorCode)
        }
        assertEquals(STARTING_BALANCE - 1000, getBalance(userId))
        assertEquals(1, queryForInt("select count(*) from item_purchases where user_id = ?", userId))
        assertEquals(REQUEST_COUNT - 1, countAttempts(userId, "CART_MISMATCH"))
    }

    @Test
    fun `구매와 모두 받기가 동시에 들어와도 교착 없이 모두 끝난다`() {
        val userId = createUser()
        val products = List(REQUEST_COUNT) { createProduct(price = 100) }
        products.forEach { createCartItem(userId, it, quantity = 1) }
        repeat(REQUEST_COUNT) {
            mailService.createRewardBoxMail(
                userId = userId,
                rewardBoxCategory = RewardBoxCategory.BRONZE,
                coinQuantity = 10,
                content = "테스트 보상",
                expiresAt = null,
            )
        }

        val tasks: List<() -> Unit> = products.flatMap { product ->
            listOf(
                { purchase(userId, listOf(product to 1)) },
                { mailService.claimMails(userId) },
            )
        }
        val results = runConcurrently(tasks.size) { index -> tasks[index]() }

        results.forEach { it.getOrThrow() }
        assertEquals(REQUEST_COUNT, queryForInt("select count(*) from item_purchases where user_id = ?", userId))
        assertEquals(STARTING_BALANCE - 100 * REQUEST_COUNT + 10 * REQUEST_COUNT, getBalance(userId))
    }

    @Test
    fun `잔액이 모자라게 동시에 사면 잔액만큼만 사고 나머지는 잔액 부족으로 거부한다`() {
        val userId = createUser(balance = 1000)
        val products = List(REQUEST_COUNT) { createProduct(price = 300) }
        products.forEach { createCartItem(userId, it, quantity = 1) }

        val results = runConcurrently(REQUEST_COUNT) { index -> purchase(userId, listOf(products[index] to 1)) }

        assertEquals(3, results.count { it.isSuccess })
        results.mapNotNull { it.exceptionOrNull() }.forEach { failure ->
            assertEquals(ShopErrorCode.INSUFFICIENT_BALANCE, (failure as BusinessException).errorCode)
        }
        assertEquals(100, getBalance(userId))
        assertEquals(3, queryForInt("select count(*) from item_purchases where user_id = ?", userId))
        assertEquals(3, countAttempts(userId, "SUCCEEDED"))
        assertEquals(REQUEST_COUNT - 3, countAttempts(userId, "INSUFFICIENT_BALANCE"))
        assertCoinHistoriesChained(userId, startingBalance = 1000)
    }

    @Test
    fun `거부되면 구매와 잔액, 장바구니는 그대로고 거부 기록만 남는다`() {
        val userId = createUser()
        val product = createProduct(price = 1000)
        createCartItem(userId, product, quantity = 1)

        assertBusinessException(ShopErrorCode.PRICE_CHANGED) {
            itemPurchaseService.createItemPurchase(
                userId,
                UUID.randomUUID().toString(),
                CreateItemPurchaseRequest(listOf(ItemPurchaseProductRequest(product.id, 1, 900))),
                "1.0.0",
            )
        }

        assertEquals(STARTING_BALANCE, getBalance(userId))
        assertEquals(0, queryForInt("select count(*) from item_purchases where user_id = ?", userId))
        assertEquals(0, queryForInt("select count(*) from mails where user_id = ?", userId))
        assertEquals(1, cartItemRepository.countByUserId(userId))
        assertEquals(1, countAttempts(userId, "PRICE_CHANGED"))
    }

    @Test
    fun `같은 키로 다른 구매를 보내면 거부하고 처음 구매는 그대로다`() {
        val userId = createUser()
        val first = createProduct(price = 1000)
        val second = createProduct(price = 500)
        createCartItem(userId, first, quantity = 1)
        createCartItem(userId, second, quantity = 1)
        val idempotencyKey = UUID.randomUUID().toString()
        purchase(userId, listOf(first to 1), idempotencyKey)

        assertBusinessException(ShopErrorCode.IDEMPOTENCY_KEY_REUSED) {
            purchase(userId, listOf(second to 1), idempotencyKey)
        }

        assertEquals(STARTING_BALANCE - 1000, getBalance(userId))
        assertEquals(1, cartItemRepository.countByUserId(userId))
        assertEquals(1, countAttempts(userId, "IDEMPOTENCY_KEY_REUSED"))
    }

    @Test
    fun `코인 잠금을 제시간에 얻지 못하면 처리 중으로 거부하고 기록을 남긴다`() {
        val userId = createUser()
        val product = createProduct(price = 1000)
        createCartItem(userId, product, quantity = 1)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val holder = executor.submit {
            transactionTemplate.executeWithoutResult {
                coinService.lockCoin(userId)
                locked.countDown()
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }

        try {
            assertTrue(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            assertBusinessException(ShopErrorCode.PURCHASE_IN_PROGRESS) { purchase(userId, listOf(product to 1)) }
        } finally {
            release.countDown()
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            executor.shutdown()
        }

        assertEquals(STARTING_BALANCE, getBalance(userId))
        assertEquals(1, cartItemRepository.countByUserId(userId))
        assertEquals(1, countAttempts(userId, "PURCHASE_IN_PROGRESS"))
    }

    @Test
    fun `산 방 배경은 우편을 받기 전이나 받은 뒤나 다시 담을 수 없다`() {
        val userId = createUser()
        val background = createProduct(price = 1000, category = RoomItemCategory.BACKGROUND)
        createCartItem(userId, background, quantity = 1)
        purchase(userId, listOf(background to 1))

        assertBusinessException(ShopErrorCode.ALREADY_OWNED) {
            cartService.createCartItem(userId, CreateCartItemRequest(background.id, 1))
        }

        mailService.claimMails(userId)

        assertBusinessException(ShopErrorCode.ALREADY_OWNED) {
            cartService.createCartItem(userId, CreateCartItemRequest(background.id, 1))
        }
    }

    @Test
    fun `상품 카드의 보유 개수는 우편을 받기 전이나 받은 뒤나 산 만큼 늘어 있다`() {
        val userId = createUser()
        val product = createProduct(price = 1000)
        assertEquals(0, getOwnedCount(userId, product))
        createCartItem(userId, product, quantity = 2)

        purchase(userId, listOf(product to 2))
        assertEquals(2, getOwnedCount(userId, product))

        mailService.claimMails(userId)
        assertEquals(2, getOwnedCount(userId, product))
    }

    @Test
    fun `1인 구매 한도까지 산 개수를 세어 넘게 담거나 다시 담지 못한다`() {
        val userId = createUser()
        val limited = createProduct(price = 1000, purchaseLimit = 2)
        createCartItem(userId, limited, quantity = 1)
        purchase(userId, listOf(limited to 1))

        assertBusinessException(ShopErrorCode.CART_QUANTITY_EXCEEDED) {
            cartService.createCartItem(userId, CreateCartItemRequest(limited.id, 2))
        }

        cartService.createCartItem(userId, CreateCartItemRequest(limited.id, 1))
        purchase(userId, listOf(limited to 1))

        assertBusinessException(ShopErrorCode.PURCHASE_LIMIT_EXCEEDED) {
            cartService.createCartItem(userId, CreateCartItemRequest(limited.id, 1))
        }
    }

    @Test
    fun `첫 구매 프로모션은 할인을 적용해 기록하고 산 뒤에는 대상이 아니다`() {
        val userId = createUser()
        val product = createProduct(price = 1000)
        val promotion = promotionRepository.save(
            Promotion(
                name = "첫 구매 혜택",
                category = PromotionCategory.FIRST_PURCHASE,
                discountRate = 30,
                startsAt = now().minus(Duration.ofDays(1)),
            ),
        )
        promotionProductRepository.save(PromotionProduct(promotion.id, product.id, 0))
        createCartItem(userId, product, quantity = 1)
        assertTrue(shopItemService.getEligiblePromotions(userId, now()).any { it.id == promotion.id })

        val response = itemPurchaseService.createItemPurchase(
            userId,
            UUID.randomUUID().toString(),
            CreateItemPurchaseRequest(listOf(ItemPurchaseProductRequest(product.id, 1, 700))),
            null,
        )

        assertEquals(700, response.purchasePrice)
        assertEquals(
            promotion.id,
            queryForInt(
                "select promotion_id from item_purchase_products where item_purchase_id = ?",
                response.itemPurchaseId,
            ).toLong(),
        )
        assertTrue(shopItemService.getEligiblePromotions(userId, now()).none { it.id == promotion.id })
    }

    @Test
    fun `세트는 구성품을 모두 환불해야 1인 구매 한도에서 빠진다`() {
        val userId = createUser()
        val set = createProduct(price = 1000, itemCount = 2, purchaseLimit = 1)
        createCartItem(userId, set, quantity = 1)
        purchase(userId, listOf(set to 1))
        val (firstLineId, secondLineId) = getItemPurchaseLineIds(userId)
        assertEquals(ShopErrorCode.PURCHASE_LIMIT_EXCEEDED, getUnavailableErrorCode(userId, set))

        refundLine(firstLineId)

        assertEquals(ShopErrorCode.PURCHASE_LIMIT_EXCEEDED, getUnavailableErrorCode(userId, set))

        refundLine(secondLineId)

        assertNull(getUnavailableErrorCode(userId, set))
    }

    @Test
    fun `같은 상품을 여러 개 사면 환불한 개수만큼만 1인 구매 한도가 돌아온다`() {
        val userId = createUser()
        val limited = createProduct(price = 1000, purchaseLimit = 2)
        createCartItem(userId, limited, quantity = 2)
        purchase(userId, listOf(limited to 2))
        assertEquals(ShopErrorCode.PURCHASE_LIMIT_EXCEEDED, getUnavailableErrorCode(userId, limited))

        refundLine(getItemPurchaseLineIds(userId).first())

        assertNull(getUnavailableErrorCode(userId, limited))
        assertEquals(ShopErrorCode.PURCHASE_LIMIT_EXCEEDED, getUnavailableErrorCode(userId, limited, quantity = 2))
    }

    @Test
    fun `받기 전에 회수한 아이템은 보유 개수에서 빠지고 우편을 받아도 생기지 않는다`() {
        val userId = createUser()
        val product = createProduct(price = 500)
        createCartItem(userId, product, quantity = 2)
        purchase(userId, listOf(product to 2))
        assertEquals(2, getOwnedCount(userId, product))

        revokeRow("mail_items", getFirstMailItemId(userId))

        assertEquals(1, getOwnedCount(userId, product))

        val reward = mailService.claimMails(userId)

        assertEquals(1, reward.items?.single()?.quantity)
        assertEquals(1, queryForInt("select count(*) from owned_room_items where user_id = ?", userId))
        assertEquals(1, getOwnedCount(userId, product))
    }

    @Test
    fun `회수한 방 배경은 우편을 받기 전이나 받은 뒤나 다시 살 수 있다`() {
        val userId = createUser()
        val background = createProduct(price = 500, category = RoomItemCategory.BACKGROUND)
        val otherUserId = createUser()
        createCartItem(userId, background, quantity = 1)
        createCartItem(otherUserId, background, quantity = 1)
        purchase(userId, listOf(background to 1))
        purchase(otherUserId, listOf(background to 1))
        mailService.claimMails(otherUserId)
        assertEquals(ShopErrorCode.ALREADY_OWNED, getUnavailableErrorCode(userId, background))
        assertEquals(ShopErrorCode.ALREADY_OWNED, getUnavailableErrorCode(otherUserId, background))

        revokeRow("mail_items", getFirstMailItemId(userId))
        revokeRow("owned_room_items", getFirstOwnedRoomItemId(otherUserId))

        assertNull(getUnavailableErrorCode(userId, background))
        assertNull(getUnavailableErrorCode(otherUserId, background))
    }

    @Test
    fun `받은 뒤 회수한 아이템은 장착 중이었어도 마이룸과 보유 개수에서 빠지고 장착 저장을 거부한다`() {
        val userId = createUser()
        val product = createProduct(price = 500)
        createCartItem(userId, product, quantity = 2)
        purchase(userId, listOf(product to 2))
        mailService.claimMails(userId)
        val revokedId = getFirstOwnedRoomItemId(userId)
        val remainingId = roomService.getOwnedRoomItems(userId, RoomItemCategory.BED, null, 10)
            .ownedRoomItems.map { it.ownedRoomItemId }.single { it != revokedId }
        roomService.updateEquippedRoomItems(userId, createEquippedRoomItemsRequest(revokedId, remainingId))

        // 장착 해제는 환불이 하지만, 조회는 장착 상태와 관계없이 회수된 행을 빼야 한다
        revokeRow("owned_room_items", revokedId)

        val ownedRoomItems = roomService.getOwnedRoomItems(userId, RoomItemCategory.BED, null, 10).ownedRoomItems
        assertEquals(listOf(remainingId), ownedRoomItems.map { it.ownedRoomItemId })
        assertEquals(listOf(remainingId), roomService.getEquippedRoomItems(userId).map { it.ownedRoomItemId })
        assertEquals(1, getOwnedCount(userId, product))
        assertBusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND) {
            roomService.updateEquippedRoomItems(userId, createEquippedRoomItemsRequest(revokedId))
        }
    }

    @Test
    fun `회수와 장착 저장이 겹쳐도 회수 시각이 지워지지 않는다`() {
        val userId = createUser()
        val product = createProduct(price = 500)
        createCartItem(userId, product, quantity = 2)
        purchase(userId, listOf(product to 2))
        mailService.claimMails(userId)
        val revokedId = getFirstOwnedRoomItemId(userId)
        val remainingId = roomService.getOwnedRoomItems(userId, RoomItemCategory.BED, null, 10)
            .ownedRoomItems.map { it.ownedRoomItemId }.single { it != revokedId }
        roomService.updateEquippedRoomItems(userId, createEquippedRoomItemsRequest(revokedId, remainingId))
        val revoked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // 환불과 같은 순서로 코인을 잠그고 회수한 뒤 커밋을 미룬다
        val revoker = executor.submit {
            transactionTemplate.executeWithoutResult {
                coinService.lockCoin(userId)
                revokeRow("owned_room_items", revokedId)
                revoked.countDown()
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        assertTrue(revoked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val saver = executor.submit {
            roomService.updateEquippedRoomItems(userId, createEquippedRoomItemsRequest(remainingId))
        }

        // 저장이 잠금 대기에 들어갈 시간을 준다. 잠그지 않는 코드면 회수 전 행을 읽고 UPDATE에서 기다리다가,
        // 회수가 커밋된 뒤 전체 컬럼을 써서 revoked_at을 지운다.
        Thread.sleep(500)
        release.countDown()

        revoker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        saver.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(
            1,
            queryForInt("select count(*) from owned_room_items where id = ? and revoked_at is not null", revokedId),
        )
        assertEquals(listOf(remainingId), roomService.getEquippedRoomItems(userId).map { it.ownedRoomItemId })
    }

    private fun now(): Instant = Instant.now(clock)

    private fun createUser(balance: Int = STARTING_BALANCE): Long {
        val userId = userRepository.save(
            User(provider = AuthProvider.EMAIL, email = "${UUID.randomUUID()}@nyummy.co.kr"),
        ).id
        coinRepository.save(Coin(userId = userId).apply { this.balance = balance })
        return userId
    }

    // 구성품 정가는 상품 정가를 나눠 갖지 않아도 된다. 저장되는지만 본다.
    private fun createProduct(
        price: Int,
        category: RoomItemCategory = RoomItemCategory.BED,
        itemCount: Int = 1,
        purchaseLimit: Int? = null,
    ): Product {
        val itemIds = List(itemCount) {
            val item = itemRepository.save(Item(name = "아이템", price = price))
            roomItemRepository.save(RoomItem(itemId = item.id, category = category))
            item.id
        }
        val product = productRepository.save(
            Product(
                category = category,
                name = "상품",
                representativeItemId = itemIds.first(),
                price = price,
                isLimited = false,
                saleStartsAt = now().minus(Duration.ofDays(1)),
                purchaseLimit = purchaseLimit,
            ),
        )
        itemIds.forEach { productItemRepository.save(ProductItem(product.id, it, price)) }
        return product
    }

    private fun createCartItem(userId: Long, product: Product, quantity: Int) {
        cartItemRepository.save(CartItem(userId = userId, productId = product.id, quantity = quantity))
    }

    private fun purchase(
        userId: Long,
        products: List<Pair<Product, Int>>,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): ItemPurchaseResponse = itemPurchaseService.createItemPurchase(
        userId,
        idempotencyKey,
        CreateItemPurchaseRequest(
            products.map { (product, quantity) -> ItemPurchaseProductRequest(product.id, quantity, product.price) },
        ),
        "1.0.0",
    )

    private fun getOwnedCount(userId: Long, product: Product): Int = shopItemService.createShopItems(
        userId,
        listOf(product),
        emptyMap(),
        now(),
        Language.KO,
    ).getValue(product.id).ownedCount

    private fun getUnavailableErrorCode(userId: Long, product: Product, quantity: Int = 1): ErrorCode? =
        shopItemService.getProductAvailabilities(userId, listOf(product), now())
            .getValue(product.id)
            .getUnavailableErrorCode(quantity)

    private fun getItemPurchaseLineIds(userId: Long): List<Long> = jdbcTemplate.queryForList(
        """
        select l.id from item_purchase_lines l
            join item_purchase_products p on p.id = l.item_purchase_product_id
            join item_purchases ip on ip.id = p.item_purchase_id
        where ip.user_id = ?
        order by l.id
        """,
        Long::class.javaObjectType,
        userId,
    ).filterNotNull()

    // 환불 코드가 아직 없어 환불 기록을 직접 넣는다
    private fun refundLine(itemPurchaseLineId: Long) {
        val adminId = adminRepository.save(Admin(email = "${UUID.randomUUID()}@nyummy.co.kr", name = "운영자")).id
        val itemPurchaseProductId = queryForInt(
            "select item_purchase_product_id from item_purchase_lines where id = ?",
            itemPurchaseLineId,
        )
        jdbcTemplate.update(
            """
            insert into item_purchase_refunds (item_purchase_product_id, admin_id, coin_quantity, reason)
            values (?, ?, 0, '테스트')
            """,
            itemPurchaseProductId,
            adminId,
        )
        jdbcTemplate.update(
            """
            insert into item_purchase_refund_lines (item_purchase_refund_id, item_purchase_line_id)
            values ((select max(id) from item_purchase_refunds), ?)
            """,
            itemPurchaseLineId,
        )
    }

    private fun getFirstMailItemId(userId: Long): Long = queryForInt(
        "select min(mi.id) from mail_items mi join mails m on m.id = mi.mail_id where m.user_id = ?",
        userId,
    ).toLong()

    private fun getFirstOwnedRoomItemId(userId: Long): Long =
        queryForInt("select min(id) from owned_room_items where user_id = ?", userId).toLong()

    // 환불 코드가 아직 없어 회수 시각을 직접 넣는다
    private fun revokeRow(table: String, id: Long) {
        jdbcTemplate.update("update $table set revoked_at = current_timestamp where id = ?", id)
    }

    private fun createEquippedRoomItemsRequest(vararg ownedRoomItemIds: Long) = UpdateEquippedRoomItemsRequest(
        ownedRoomItemIds.map { EquippedRoomItemRequest(it, x = 10f, y = 10f, zIndex = 0, scale = 1f) },
    )

    private fun getBalance(userId: Long): Int = assertNotNull(coinRepository.findByUserId(userId)).balance

    // 코인 내역을 쌓인 순서대로 따라가며 시작 잔액에 변동을 더한 값이 각 내역의 직후 잔액과 지금 잔액에 맞는지 본다
    private fun assertCoinHistoriesChained(userId: Long, startingBalance: Int) {
        val histories = jdbcTemplate.query(
            "select quantity, balance_after from coin_histories where user_id = ? order by id",
            { rs, _ -> rs.getInt("quantity") to rs.getInt("balance_after") },
            userId,
        )
        var balance = startingBalance
        histories.forEachIndexed { index, (quantity, balanceAfter) ->
            assertEquals(balance + quantity, balanceAfter, "${index + 1}번째 코인 내역이 앞 내역의 잔액과 이어지지 않습니다")
            balance = balanceAfter
        }
        assertEquals(balance, getBalance(userId), "마지막 코인 내역의 잔액이 지금 잔액과 다릅니다")
    }

    private fun queryForInt(sql: String, id: Long): Int =
        assertNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, id))

    private fun countAttempts(userId: Long, outcome: String): Int = assertNotNull(
        jdbcTemplate.queryForObject(
            "select count(*) from item_purchase_attempts where user_id = ? and outcome = ?",
            Int::class.java,
            userId,
            outcome,
        ),
    )

    // 모든 스레드가 출발선에 선 것을 확인한 뒤 한꺼번에 시작한다. 확인하지 않으면 늦게 뜬 스레드는 열린 문을 지나
    // 앞 요청이 끝난 뒤에 시작할 수 있어, 겹치지 않은 채 통과한다.
    private fun <T> runConcurrently(taskCount: Int, task: (Int) -> T): List<Result<T>> {
        val ready = CountDownLatch(taskCount)
        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(taskCount)
        val futures = (0 until taskCount).map { index ->
            executor.submit<Result<T>> {
                ready.countDown()
                startGate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                runCatching { task(index) }
            }
        }
        assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "스레드가 제시간에 준비되지 않았습니다")
        startGate.countDown()
        val results = futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        executor.shutdown()
        return results
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }
}

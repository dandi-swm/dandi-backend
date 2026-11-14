package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.dto.CreateItemPurchaseRefundRequest
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundResponse
import com.dandi.nyummy.admin.entity.Admin
import com.dandi.nyummy.admin.repository.AdminRepository
import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.repository.CoinRepository
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.RoomErrorCode
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
import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseProductRequest
import com.dandi.nyummy.shop.entity.CartItem
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ProductItem
import com.dandi.nyummy.shop.enum.ItemPurchaseLineStatus
import com.dandi.nyummy.shop.enum.RefundPolicyExceptionCategory
import com.dandi.nyummy.shop.repository.CartItemRepository
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.service.ItemPurchaseService
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 환불을 실제 MySQL에서 구매부터 끝까지 돌린다. 회수가 앱의 조회와 우편 받기에 반영되는지와 코인 잠금 위의 경합을 확인한다.
 * 어드민 구매 내역도 같은 흐름으로 만든 데이터에서 구성품 상태를 제대로 판단하는지 확인한다.
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "app.admin.enabled=true",
        "app.admin.google-client-id=test-client-id",
        "app.admin.cdn-distribution-id=test-distribution-id",
    ],
)
@ActiveProfiles("test")
class AdminItemPurchaseRefundIntegrationTest @Autowired constructor(
    private val adminItemPurchaseRefundService: AdminItemPurchaseRefundService,
    private val adminItemPurchaseService: AdminItemPurchaseService,
    private val itemPurchaseService: ItemPurchaseService,
    private val mailService: MailService,
    private val coinService: CoinService,
    private val roomService: RoomService,
    private val userRepository: UserRepository,
    private val adminRepository: AdminRepository,
    private val coinRepository: CoinRepository,
    private val itemRepository: ItemRepository,
    private val roomItemRepository: RoomItemRepository,
    private val productRepository: ProductRepository,
    private val productItemRepository: ProductItemRepository,
    private val cartItemRepository: CartItemRepository,
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
        private const val STARTING_BALANCE = 10_000
        private const val REQUEST_COUNT = 8
    }

    @Test
    fun `받기 전 단품을 환불하면 코인이 돌아오고 우편이 사라지며 코인 내역이 남는다`() {
        val userId = createUser()
        purchase(userId, createProduct(listOf(500)))

        val response = createRefund(getItemPurchaseLineIds(userId))

        assertEquals(500, response.coinQuantity)
        assertEquals(STARTING_BALANCE, response.balance)
        assertEquals(STARTING_BALANCE, getBalance(userId))
        assertFalse(mailService.existsNotClaimedMail(userId))
        assertEquals(emptyList(), mailService.getMails(userId, null, 10).mails)
        assertNull(mailService.claimMails(userId, Language.KO).items)
        assertEquals(0, countOwnedRoomItems(userId))
        val coinHistory = coinService.getCoinHistories(userId, null, 10, Language.KO).coinHistories.first()
        assertEquals("상점 환불", coinHistory.title)
        assertEquals(500, coinHistory.quantity)
        assertEquals(STARTING_BALANCE, coinHistory.balanceAfter)
    }

    @Test
    fun `받기 전 세트를 일부만 환불하면 우편이 남고 나머지 구성품만 받는다`() {
        val userId = createUser()
        purchase(userId, createProduct(listOf(500, 300), purchasePrice = 600))
        val (firstLineId) = getItemPurchaseLineIds(userId)

        val response = createRefund(listOf(firstLineId))

        assertEquals(300, response.coinQuantity)
        assertEquals(1, mailService.getMails(userId, null, 10).mails.size)
        assertEquals(1, mailService.claimMails(userId, Language.KO).items?.single()?.quantity)
        assertEquals(1, countOwnedRoomItems(userId))
        assertEquals(0, countActiveOwnedRoomItems(userId, firstLineId))
    }

    @Test
    fun `세트를 나눠서 환불하면 할인을 취소한 금액을 돌려주고 합계는 결제가와 같다`() {
        val userId = createUser()
        purchase(userId, createProduct(listOf(500, 300, 200), purchasePrice = 800))
        mailService.claimMails(userId, Language.KO)
        val lineIds = getItemPurchaseLineIds(userId)

        val refunded = lineIds.map { createRefund(listOf(it)).coinQuantity }

        assertEquals(listOf(300, 300, 200), refunded)
        assertEquals(STARTING_BALANCE, getBalance(userId))
    }

    @Test
    fun `장착한 아이템은 예외 표시 없이 거부되고 아무것도 바뀌지 않는다`() {
        val userId = createUser()
        purchase(userId, createProduct(listOf(500)))
        mailService.claimMails(userId, Language.KO)
        val lineIds = getItemPurchaseLineIds(userId)
        val ownedRoomItemId = getOwnedRoomItemId(userId)
        equip(userId, ownedRoomItemId)

        assertBusinessException(AdminErrorCode.REFUND_POLICY_EXCEPTION_REQUIRED) {
            createRefund(lineIds)
        }

        assertEquals(STARTING_BALANCE - 500, getBalance(userId))
        assertEquals(0, queryForInt("select count(*) from item_purchase_refunds where admin_id = ?", adminId))
        assertEquals(listOf(ownedRoomItemId), roomService.getEquippedRoomItems(userId).map { it.ownedRoomItemId })
    }

    @Test
    fun `장착한 아이템을 예외로 환불하면 마이룸에서 빠지고 사용자가 지워도 환불할 수 있다`() {
        val userId = createUser()
        val product = createProduct(listOf(500))
        purchase(userId, product, quantity = 2)
        mailService.claimMails(userId, Language.KO)
        val (equippedLineId, deletedLineId) = getItemPurchaseLineIds(userId)
        val equippedId = getOwnedRoomItemId(userId, equippedLineId)
        val deletedId = getOwnedRoomItemId(userId, deletedLineId)
        equip(userId, equippedId)
        roomService.deleteOwnedRoomItem(userId, deletedId)

        val equippedRefund = adminItemPurchaseRefundService.createRefund(
            adminId,
            getItemPurchaseProductId(equippedLineId),
            CreateItemPurchaseRefundRequest(
                listOf(equippedLineId),
                "실수 구매",
                expectedCoinQuantity = 500,
                isPolicyException = true,
            ),
        )
        val deletedRefund = adminItemPurchaseRefundService.createRefund(
            adminId,
            getItemPurchaseProductId(deletedLineId),
            CreateItemPurchaseRefundRequest(listOf(deletedLineId), "실수 구매", expectedCoinQuantity = 500),
        )

        assertEquals(500, equippedRefund.coinQuantity)
        assertEquals(500, deletedRefund.coinQuantity)
        assertEquals(emptyList(), roomService.getEquippedRoomItems(userId))
        assertEquals(STARTING_BALANCE, getBalance(userId))
        assertBusinessException(RoomErrorCode.OWNED_ROOM_ITEM_NOT_FOUND) {
            roomService.restoreOwnedRoomItem(userId, deletedId)
        }
    }

    @Test
    fun `미리 보기는 구성품별 예외와 전체 예외를 알려 주고 아무것도 바꾸지 않는다`() {
        val userId = createUser()
        val product = createProduct(listOf(500, 300), purchasePrice = 800)
        purchase(userId, product)
        mailService.claimMails(userId, Language.KO)
        val (equippedLineId, otherLineId) = getItemPurchaseLineIds(userId)
        equip(userId, getOwnedRoomItemId(userId, equippedLineId))
        expirePurchase(userId)

        val preview = adminItemPurchaseRefundService.getRefundPreview(
            getItemPurchaseProductId(equippedLineId),
            listOf(otherLineId, equippedLineId),
        )

        assertEquals(800, preview.coinQuantity)
        assertEquals(
            listOf(RefundPolicyExceptionCategory.PERIOD_EXPIRED, RefundPolicyExceptionCategory.EQUIPPED),
            preview.policyExceptions,
        )
        assertEquals(listOf(otherLineId, equippedLineId), preview.lines.map { it.itemPurchaseLineId })
        assertEquals(listOf(RefundPolicyExceptionCategory.PERIOD_EXPIRED), preview.lines.first().policyExceptions)
        assertEquals(
            listOf(RefundPolicyExceptionCategory.PERIOD_EXPIRED, RefundPolicyExceptionCategory.EQUIPPED),
            preview.lines.last().policyExceptions,
        )
        assertEquals(STARTING_BALANCE - 800, getBalance(userId))
        assertEquals(2, countOwnedRoomItems(userId))
    }

    @Test
    fun `같은 구성품을 동시에 환불해도 한 번만 돌려주고 나머지는 거부한다`() {
        val userId = createUser()
        purchase(userId, createProduct(listOf(500)))
        val lineIds = getItemPurchaseLineIds(userId)

        val results = runConcurrently(
            { createRefund(lineIds) },
            { createRefund(lineIds) },
        )

        assertEquals(1, results.count { it.isSuccess })
        val rejected = results.mapNotNull { it.exceptionOrNull() }.single() as BusinessException
        assertEquals(AdminErrorCode.ALREADY_REFUNDED, rejected.errorCode)
        assertEquals(STARTING_BALANCE, getBalance(userId))
    }

    @Test
    fun `환불과 우편 받기가 겹쳐도 코인은 한 번만 돌아오고 아이템은 남지 않는다`() {
        repeat(5) {
            val userId = createUser()
            purchase(userId, createProduct(listOf(500)))
            val lineIds = getItemPurchaseLineIds(userId)

            val results = runConcurrently(
                { createRefund(lineIds) },
                { mailService.claimMails(userId, Language.KO) },
            )

            results.forEach { it.getOrThrow() }
            assertEquals(STARTING_BALANCE, getBalance(userId))
            assertEquals(0, countActiveOwnedRoomItems(userId, lineIds.single()))
        }
    }

    @Test
    fun `미리 본 뒤 같은 세트의 다른 구성품이 환불되면 거부하고 다시 본 금액으로 환불한다`() {
        val userId = createUser()
        purchase(userId, createProduct(listOf(500, 300, 200), purchasePrice = 800))
        val (firstLineId, secondLineId) = getItemPurchaseLineIds(userId)
        val itemPurchaseProductId = getItemPurchaseProductId(firstLineId)
        val preview = adminItemPurchaseRefundService.getRefundPreview(itemPurchaseProductId, listOf(firstLineId))
        assertEquals(300, preview.coinQuantity)
        assertEquals(100, createRefund(listOf(secondLineId)).coinQuantity)
        val balanceBeforeRejection = getBalance(userId)

        assertBusinessException(AdminErrorCode.REFUND_COIN_QUANTITY_CHANGED) {
            adminItemPurchaseRefundService.createRefund(
                adminId,
                itemPurchaseProductId,
                CreateItemPurchaseRefundRequest(listOf(firstLineId), "실수 구매", preview.coinQuantity),
            )
        }

        assertEquals(balanceBeforeRejection, getBalance(userId))
        assertEquals(
            0,
            queryForInt("select count(*) from item_purchase_refund_lines where item_purchase_line_id = ?", firstLineId),
        )
        assertEquals(500, createRefund(listOf(firstLineId)).coinQuantity)
        assertEquals(balanceBeforeRejection + 500, getBalance(userId))
    }

    @Test
    fun `같은 세트의 다른 구성품을 동시에 환불하면 먼저 처리된 쪽만 미리 본 금액으로 환불한다`() {
        repeat(5) {
            val userId = createUser()
            purchase(userId, createProduct(listOf(500, 300, 200), purchasePrice = 800))
            val (firstLineId, secondLineId) = getItemPurchaseLineIds(userId)
            val itemPurchaseProductId = getItemPurchaseProductId(firstLineId)
            val requests = listOf(firstLineId, secondLineId).map { lineId ->
                val preview = adminItemPurchaseRefundService.getRefundPreview(itemPurchaseProductId, listOf(lineId))
                CreateItemPurchaseRefundRequest(listOf(lineId), "실수 구매", preview.coinQuantity)
            }

            val results = runConcurrently(
                { adminItemPurchaseRefundService.createRefund(adminId, itemPurchaseProductId, requests[0]) },
                { adminItemPurchaseRefundService.createRefund(adminId, itemPurchaseProductId, requests[1]) },
            )

            val refunded = results.single { it.isSuccess }.getOrThrow() as ItemPurchaseRefundResponse
            val rejected = results.mapNotNull { it.exceptionOrNull() }.single() as BusinessException
            assertEquals(AdminErrorCode.REFUND_COIN_QUANTITY_CHANGED, rejected.errorCode)
            assertEquals(STARTING_BALANCE - 800 + refunded.coinQuantity, getBalance(userId))
        }
    }

    @Test
    fun `같은 사용자의 다른 구매 상품을 동시에 환불하면 모두 돌려주고 잔액이 시작 잔액으로 돌아온다`() {
        repeat(5) {
            val userId = createUser()
            repeat(REQUEST_COUNT) { purchase(userId, createProduct(listOf(500))) }
            // 미리 보기를 동시 실행 전에 끝내 환불 트랜잭션끼리 코인 잠금 앞에서 겹치게 한다
            val tasks: List<() -> Any> = getItemPurchaseLineIds(userId).map { lineId ->
                val itemPurchaseProductId = getItemPurchaseProductId(lineId)
                val preview = adminItemPurchaseRefundService.getRefundPreview(itemPurchaseProductId, listOf(lineId))
                val request = CreateItemPurchaseRefundRequest(listOf(lineId), "실수 구매", preview.coinQuantity)
                return@map { adminItemPurchaseRefundService.createRefund(adminId, itemPurchaseProductId, request) }
            }

            val results = runConcurrently(*tasks.toTypedArray())

            results.forEach { it.getOrThrow() }
            assertEquals(STARTING_BALANCE, getBalance(userId))
            assertEquals(
                REQUEST_COUNT,
                queryForInt(
                    """
                    select count(*) from item_purchase_refunds r
                        join item_purchase_products p on p.id = r.item_purchase_product_id
                        join item_purchases ip on ip.id = p.item_purchase_id
                    where ip.user_id = ?
                    """,
                    userId,
                ),
            )
            assertCoinHistoriesChained(userId, startingBalance = STARTING_BALANCE)
        }
    }

    @Test
    fun `구매와 환불과 우편 받기를 섞어 동시에 보내도 모두 끝나고 코인 내역이 이어진다`() {
        repeat(5) {
            // 작업마다 3개씩 9개라 커넥션 풀(10)을 넘지 않아 모두 코인 잠금 앞에서 겹친다
            val requestCount = 3
            val userId = createUser()
            repeat(requestCount) { purchase(userId, createProduct(listOf(500))) }
            val refundLineIds = getItemPurchaseLineIds(userId)
            val mailIds = List(requestCount) {
                mailService.createMealRewardMail(
                    userId = userId,
                    rewardBoxCategory = RewardBoxCategory.BRONZE,
                    coinQuantity = 10,
                    language = Language.KO,
                    expiresAt = Instant.now(clock).plus(Duration.ofDays(7)),
                ).id
            }
            val products = List(requestCount) { createProduct(listOf(300)) }
            products.forEach { cartItemRepository.save(CartItem(userId = userId, productId = it.id, quantity = 1)) }

            // 미리 보기를 동시 실행 전에 끝내 환불도 트랜잭션만 겹치게 한다
            val refunds: List<() -> Any> = refundLineIds.map { lineId ->
                val itemPurchaseProductId = getItemPurchaseProductId(lineId)
                val preview = adminItemPurchaseRefundService.getRefundPreview(itemPurchaseProductId, listOf(lineId))
                val request = CreateItemPurchaseRefundRequest(listOf(lineId), "실수 구매", preview.coinQuantity)
                return@map { adminItemPurchaseRefundService.createRefund(adminId, itemPurchaseProductId, request) }
            }
            val claims: List<() -> Any> = mailIds.map { mailId ->
                { mailService.claimMail(userId, mailId, Language.KO) }
            }
            val purchases: List<() -> Any> = products.map { product ->
                {
                    itemPurchaseService.createItemPurchase(
                        userId,
                        UUID.randomUUID().toString(),
                        CreateItemPurchaseRequest(listOf(ItemPurchaseProductRequest(product.id, 1, product.price))),
                        "1.0.0",
                        Language.KO,
                    )
                }
            }

            val results = runConcurrently(*(refunds + claims + purchases).toTypedArray())

            results.forEach { it.getOrThrow() }
            // 미리 산 상품은 모두 환불되어 빠진 만큼 돌아오고, 우편 코인이 더해지고 새로 산 만큼 빠진다
            assertEquals(STARTING_BALANCE + 10 * requestCount - 300 * requestCount, getBalance(userId))
            assertCoinHistoriesChained(userId, startingBalance = STARTING_BALANCE)
        }
    }

    @Test
    fun `구매 내역은 구성품마다 받기 전과 받음과 장착과 환불 상태를 알려 준다`() {
        val userId = createUser()
        purchase(userId, createProduct(listOf(500, 300, 200), purchasePrice = 1000))
        mailService.claimMails(userId, Language.KO)
        val (refundedLineId, equippedLineId, claimedLineId) = getItemPurchaseLineIds(userId)
        equip(userId, getOwnedRoomItemId(userId, equippedLineId))
        createRefund(listOf(refundedLineId))
        purchase(userId, createProduct(listOf(400)))

        val response = adminItemPurchaseService.getItemPurchases(userId, null, 10)

        val (newest, oldest) = response.itemPurchases
        val notClaimedLine = newest.products.single().lines.single()
        assertEquals(ItemPurchaseLineStatus.NOT_CLAIMED, notClaimedLine.status)
        assertFalse(notClaimedLine.isEquipped)
        val product = oldest.products.single()
        assertEquals("상품", product.productName)
        assertEquals(listOf(refundedLineId, equippedLineId, claimedLineId), product.lines.map { it.itemPurchaseLineId })
        assertEquals(
            listOf(
                ItemPurchaseLineStatus.REFUNDED,
                ItemPurchaseLineStatus.CLAIMED,
                ItemPurchaseLineStatus.CLAIMED,
            ),
            product.lines.map { it.status },
        )
        assertEquals(listOf(false, true, false), product.lines.map { it.isEquipped })
        assertEquals("아이템", product.lines.first().itemName)
        val refund = product.refunds.single()
        assertEquals(500, refund.coinQuantity)
        assertEquals("실수 구매", refund.reason)
    }

    @Test
    fun `구매 내역에서 장착한 채 환불한 구성품은 장착이 아니고 사용자가 지운 아이템은 받은 상태다`() {
        val userId = createUser()
        purchase(userId, createProduct(listOf(500)), quantity = 2)
        mailService.claimMails(userId, Language.KO)
        val (equippedLineId, deletedLineId) = getItemPurchaseLineIds(userId)
        equip(userId, getOwnedRoomItemId(userId, equippedLineId))
        roomService.deleteOwnedRoomItem(userId, getOwnedRoomItemId(userId, deletedLineId))
        adminItemPurchaseRefundService.createRefund(
            adminId,
            getItemPurchaseProductId(equippedLineId),
            CreateItemPurchaseRefundRequest(
                listOf(equippedLineId),
                "실수 구매",
                expectedCoinQuantity = 500,
                isPolicyException = true,
            ),
        )

        val lines = adminItemPurchaseService.getItemPurchases(userId, null, 10)
            .itemPurchases.single().products.flatMap { it.lines }

        assertEquals(
            listOf(ItemPurchaseLineStatus.REFUNDED, ItemPurchaseLineStatus.CLAIMED),
            lines.map { it.status },
        )
        assertEquals(listOf(false, false), lines.map { it.isEquipped })
    }

    @Test
    fun `구매 내역은 최신순으로 커서를 따라 조회하고 다른 사용자의 구매는 섞이지 않는다`() {
        val userId = createUser()
        val otherUserId = createUser()
        repeat(3) { purchase(userId, createProduct(listOf(100))) }
        purchase(otherUserId, createProduct(listOf(100)))

        val firstPage = adminItemPurchaseService.getItemPurchases(userId, null, 2)
        val secondPage = adminItemPurchaseService.getItemPurchases(userId, firstPage.nextCursor, 2)

        val itemPurchaseIds = firstPage.itemPurchases.map { it.itemPurchaseId } +
            secondPage.itemPurchases.map { it.itemPurchaseId }
        assertEquals(3, itemPurchaseIds.size)
        assertEquals(itemPurchaseIds.sortedDescending(), itemPurchaseIds)
        assertEquals(true, firstPage.hasNext)
        assertEquals(firstPage.itemPurchases.last().itemPurchaseId, firstPage.nextCursor)
        assertFalse(secondPage.hasNext)
        assertNull(secondPage.nextCursor)
        assertEquals(1, adminItemPurchaseService.getItemPurchases(otherUserId, null, 10).itemPurchases.size)
    }

    @Test
    fun `구매가 없는 사용자의 구매 내역은 비어 있다`() {
        val response = adminItemPurchaseService.getItemPurchases(createUser(), null, 10)

        assertEquals(emptyList(), response.itemPurchases)
        assertFalse(response.hasNext)
    }

    private val adminId: Long by lazy {
        adminRepository.save(Admin(email = "${UUID.randomUUID()}@nyummy.co.kr", name = "운영자")).id
    }

    private fun createUser(): Long {
        val userId = userRepository.save(
            User(provider = AuthProvider.EMAIL, email = "${UUID.randomUUID()}@nyummy.co.kr"),
        ).id
        coinRepository.save(Coin(userId = userId).apply { balance = STARTING_BALANCE })
        return userId
    }

    private fun createProduct(listPrices: List<Int>, purchasePrice: Int = listPrices.sum()): Product {
        val itemIds = listPrices.map { listPrice ->
            val item = itemRepository.save(Item(name = "아이템", price = listPrice))
            roomItemRepository.save(RoomItem(itemId = item.id, category = RoomItemCategory.BED))
            item.id
        }
        val product = productRepository.save(
            Product(
                category = RoomItemCategory.BED,
                name = "상품",
                representativeItemId = itemIds.first(),
                price = purchasePrice,
                isLimited = false,
                saleStartsAt = Instant.now(clock).minus(Duration.ofDays(1)),
            ),
        )
        itemIds.zip(listPrices).forEach { (itemId, listPrice) ->
            productItemRepository.save(ProductItem(product.id, itemId, listPrice))
        }
        return product
    }

    private fun purchase(userId: Long, product: Product, quantity: Int = 1) {
        cartItemRepository.save(CartItem(userId = userId, productId = product.id, quantity = quantity))
        itemPurchaseService.createItemPurchase(
            userId,
            UUID.randomUUID().toString(),
            CreateItemPurchaseRequest(listOf(ItemPurchaseProductRequest(product.id, quantity, product.price))),
            "1.0.0",
            Language.KO,
        )
    }

    // 어드민 화면처럼 미리 본 금액을 그대로 보낸다
    private fun createRefund(itemPurchaseLineIds: List<Long>): ItemPurchaseRefundResponse {
        val itemPurchaseProductId = getItemPurchaseProductId(itemPurchaseLineIds.first())
        val preview = adminItemPurchaseRefundService.getRefundPreview(itemPurchaseProductId, itemPurchaseLineIds)

        return adminItemPurchaseRefundService.createRefund(
            adminId,
            itemPurchaseProductId,
            CreateItemPurchaseRefundRequest(itemPurchaseLineIds, "실수 구매", preview.coinQuantity),
        )
    }

    private fun equip(userId: Long, ownedRoomItemId: Long) {
        roomService.updateEquippedRoomItems(
            userId,
            UpdateEquippedRoomItemsRequest(
                listOf(EquippedRoomItemRequest(ownedRoomItemId, x = 10f, y = 10f, zIndex = 0, scale = 1f)),
            ),
        )
    }

    private fun expirePurchase(userId: Long) {
        jdbcTemplate.update(
            "update item_purchases set created_at = date_sub(current_timestamp, interval 8 day) where user_id = ?",
            userId,
        )
    }

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

    private fun getItemPurchaseProductId(itemPurchaseLineId: Long): Long = queryForInt(
        "select item_purchase_product_id from item_purchase_lines where id = ?",
        itemPurchaseLineId,
    ).toLong()

    private fun getOwnedRoomItemId(userId: Long): Long =
        queryForInt("select min(id) from owned_room_items where user_id = ?", userId).toLong()

    private fun getOwnedRoomItemId(userId: Long, itemPurchaseLineId: Long): Long = assertNotNull(
        jdbcTemplate.queryForObject(
            "select id from owned_room_items where user_id = ? and item_purchase_line_id = ?",
            Long::class.javaObjectType,
            userId,
            itemPurchaseLineId,
        ),
    )

    private fun countOwnedRoomItems(userId: Long): Int =
        queryForInt("select count(*) from owned_room_items where user_id = ? and revoked_at is null", userId)

    private fun countActiveOwnedRoomItems(userId: Long, itemPurchaseLineId: Long): Int = assertNotNull(
        jdbcTemplate.queryForObject(
            """
            select count(*) from owned_room_items
            where user_id = ? and item_purchase_line_id = ? and revoked_at is null
            """,
            Int::class.java,
            userId,
            itemPurchaseLineId,
        ),
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

    // 모든 작업이 준비된 뒤 한꺼번에 시작한다
    private fun runConcurrently(vararg tasks: () -> Any): List<Result<Any>> {
        val ready = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(tasks.size)
        val futures = tasks.map { task ->
            executor.submit<Result<Any>> {
                ready.countDown()
                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                runCatching { task() }
            }
        }
        ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        start.countDown()

        val results = futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        executor.shutdown()
        return results
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException> { block() }
        assertEquals(expected, exception.errorCode)
    }
}

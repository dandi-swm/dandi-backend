package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.dto.CreateItemPurchaseRefundRequest
import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.mail.entity.Mail
import com.dandi.nyummy.mail.entity.MailItem
import com.dandi.nyummy.mail.enum.MailCategory
import com.dandi.nyummy.mail.repository.MailItemRepository
import com.dandi.nyummy.mail.repository.MailRepository
import com.dandi.nyummy.room.entity.OwnedRoomItem
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.entity.ItemPurchase
import com.dandi.nyummy.shop.entity.ItemPurchaseLine
import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import com.dandi.nyummy.shop.entity.ItemPurchaseRefund
import com.dandi.nyummy.shop.entity.ItemPurchaseRefundLine
import com.dandi.nyummy.shop.enum.RefundPolicyExceptionCategory
import com.dandi.nyummy.shop.repository.ItemPurchaseLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRefundLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRefundRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AdminItemPurchaseRefundTransactionServiceTest {

    private val userId = 1L
    private val adminId = 2L
    private var coin = Coin(userId = userId)
    private val itemPurchaseProductId = 10L
    private val purchaseId = 20L
    private val refundId = 30L
    private val now = Instant.parse("2026-11-04T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val coinService = mockk<CoinService>()
    private val itemPurchaseRepository = mockk<ItemPurchaseRepository>()
    private val itemPurchaseProductRepository = mockk<ItemPurchaseProductRepository>()
    private val itemPurchaseLineRepository = mockk<ItemPurchaseLineRepository>()
    private val itemPurchaseRefundRepository = mockk<ItemPurchaseRefundRepository>()
    private val itemPurchaseRefundLineRepository = mockk<ItemPurchaseRefundLineRepository>()
    private val ownedRoomItemRepository = mockk<OwnedRoomItemRepository>()
    private val mailRepository = mockk<MailRepository>()
    private val mailItemRepository = mockk<MailItemRepository>()

    private val service = AdminItemPurchaseRefundTransactionService(
        coinService = coinService,
        shopProperties = ShopProperties(refundTimeToLive = Duration.ofDays(7)),
        itemPurchaseRepository = itemPurchaseRepository,
        itemPurchaseProductRepository = itemPurchaseProductRepository,
        itemPurchaseLineRepository = itemPurchaseLineRepository,
        itemPurchaseRefundRepository = itemPurchaseRefundRepository,
        itemPurchaseRefundLineRepository = itemPurchaseRefundLineRepository,
        ownedRoomItemRepository = ownedRoomItemRepository,
        mailRepository = mailRepository,
        mailItemRepository = mailItemRepository,
        clock = clock,
    )

    private fun assertBusinessException(errorCode: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException> { block() }
        assertEquals(errorCode, exception.errorCode)
    }

    private fun line(id: Long, listPrice: Int) =
        ItemPurchaseLine(itemPurchaseProductId, itemId = id, listPrice = listPrice).apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    private fun ownedRoomItem(lineId: Long, firstEquippedAt: Instant? = null, isEquipped: Boolean = false) =
        OwnedRoomItem(userId, itemId = lineId, itemPurchaseLineId = lineId).apply {
            this.firstEquippedAt = firstEquippedAt
            this.isEquipped = isEquipped
        }

    private fun request(lineIds: List<Long>, expectedCoinQuantity: Int = 800, isPolicyException: Boolean = false) =
        CreateItemPurchaseRefundRequest(lineIds, "  실수 구매  ", expectedCoinQuantity, isPolicyException)

    private fun givenRefundTarget(
        purchasePrice: Int = 800,
        lines: List<ItemPurchaseLine> = listOf(line(1L, 500), line(2L, 300)),
        purchasedAt: Instant = now.minus(Duration.ofDays(1)),
        refundedLineIds: List<Long> = emptyList(),
        refundedCoinQuantity: Int? = null,
        ownedRoomItems: List<OwnedRoomItem> = emptyList(),
        balance: Int = 1000,
    ) {
        val product = ItemPurchaseProduct(purchaseId, 100L, null, purchasePrice, purchasePrice).apply {
            ReflectionTestUtils.setField(this, "id", itemPurchaseProductId)
        }
        every { itemPurchaseProductRepository.findByIdOrNull(itemPurchaseProductId) } returns product
        every { itemPurchaseRepository.getReferenceById(purchaseId) } returns
            ItemPurchase(userId, purchasePrice, "key").apply { createdAt = purchasedAt }
        every { itemPurchaseLineRepository.findByItemPurchaseProductId(itemPurchaseProductId) } returns lines
        every { itemPurchaseRefundLineRepository.findByItemPurchaseLineIdIn(any()) } returns
            refundedLineIds.map { ItemPurchaseRefundLine(refundId, it) }
        every { itemPurchaseRefundRepository.findByItemPurchaseProductId(itemPurchaseProductId) } returns
            listOfNotNull(refundedCoinQuantity?.let { ItemPurchaseRefund(itemPurchaseProductId, adminId, it, "이전 환불") })
        every { ownedRoomItemRepository.findByItemPurchaseLineIdIn(any()) } returns ownedRoomItems
        coin = Coin(userId = userId).apply { this.balance = balance }
        every { coinService.lockCoin(userId) } returns coin
    }

    private fun givenRefundSaved(balanceAfter: Int = 1800) {
        every { itemPurchaseRefundRepository.save(any()) } answers {
            firstArg<ItemPurchaseRefund>().apply { ReflectionTestUtils.setField(this, "id", refundId) }
        }
        every { itemPurchaseRefundLineRepository.saveAll(any<List<ItemPurchaseRefundLine>>()) } answers { firstArg() }
        every { mailItemRepository.findByItemPurchaseLineIdIn(any()) } returns emptyList()
        every { mailRepository.findAllById(any()) } returns emptyList()
        every { coinService.increaseBalance(any(), any(), any(), any()) } returns balanceAfter
    }

    private fun createRefund(request: CreateItemPurchaseRefundRequest) =
        service.createRefund(userId, adminId, itemPurchaseProductId, request)

    // 미리 보기

    @Test
    fun `정책 안의 환불이면 돌려줄 코인만 알려 주고 구성품별 예외는 비어 있다`() {
        givenRefundTarget(ownedRoomItems = listOf(ownedRoomItem(1L)))

        val preview = service.getRefundPreview(itemPurchaseProductId, listOf(1L, 2L))

        assertEquals(800, preview.coinQuantity)
        assertEquals(emptyList(), preview.policyExceptions)
        assertEquals(listOf(1L, 2L), preview.lines.map { it.itemPurchaseLineId })
        assertEquals(listOf(emptyList(), emptyList()), preview.lines.map { it.policyExceptions })
        verify(exactly = 0) { coinService.lockCoin(any()) }
    }

    @Test
    fun `환불 가능 기간이 지났으면 모든 구성품에 기간 초과가 붙는다`() {
        givenRefundTarget(purchasedAt = now.minus(Duration.ofDays(7)).minusSeconds(1))

        val preview = service.getRefundPreview(itemPurchaseProductId, listOf(1L, 2L))

        assertEquals(listOf(RefundPolicyExceptionCategory.PERIOD_EXPIRED), preview.policyExceptions)
        preview.lines.forEach {
            assertEquals(listOf(RefundPolicyExceptionCategory.PERIOD_EXPIRED), it.policyExceptions)
        }
    }

    @Test
    fun `딱 환불 가능 기간까지는 정책 안이다`() {
        givenRefundTarget(purchasedAt = now.minus(Duration.ofDays(7)))

        assertEquals(emptyList(), service.getRefundPreview(itemPurchaseProductId, listOf(1L)).policyExceptions)
    }

    @Test
    fun `장착 이력은 그 구성품에만 붙고 지금 해제돼 있어도 예외다`() {
        givenRefundTarget(ownedRoomItems = listOf(ownedRoomItem(2L, firstEquippedAt = now.minusSeconds(60))))

        val preview = service.getRefundPreview(itemPurchaseProductId, listOf(1L, 2L))

        assertEquals(listOf(RefundPolicyExceptionCategory.EQUIPPED), preview.policyExceptions)
        assertEquals(emptyList(), preview.lines.first { it.itemPurchaseLineId == 1L }.policyExceptions)
        assertEquals(
            listOf(RefundPolicyExceptionCategory.EQUIPPED),
            preview.lines.first { it.itemPurchaseLineId == 2L }.policyExceptions,
        )
    }

    @Test
    fun `돌려줄 코인이 0이면 전체 예외에만 붙고 구성품별 예외에는 없다`() {
        givenRefundTarget(purchasePrice = 500)

        val preview = service.getRefundPreview(itemPurchaseProductId, listOf(2L))

        assertEquals(0, preview.coinQuantity)
        assertEquals(listOf(RefundPolicyExceptionCategory.ZERO_COIN), preview.policyExceptions)
        assertEquals(emptyList(), preview.lines.single().policyExceptions)
    }

    @Test
    fun `이전 환불이 있으면 이전 환불액과 환불한 구성품을 빼고 계산한다`() {
        givenRefundTarget(
            purchasePrice = 800,
            lines = listOf(line(1L, 500), line(2L, 300), line(3L, 200)),
            refundedLineIds = listOf(1L),
            refundedCoinQuantity = 300,
        )

        assertEquals(300, service.getRefundPreview(itemPurchaseProductId, listOf(2L)).coinQuantity)
    }

    @Test
    fun `없는 구매 상품은 미리 볼 수 없다`() {
        every { itemPurchaseProductRepository.findByIdOrNull(itemPurchaseProductId) } returns null

        assertBusinessException(AdminErrorCode.ITEM_PURCHASE_PRODUCT_NOT_FOUND) {
            service.getRefundPreview(itemPurchaseProductId, listOf(1L))
        }
    }

    // 환불 실행

    @Test
    fun `코인 행을 잠근 뒤에 구매 상품을 읽고 환불 기록을 남긴 뒤 코인을 돌려준다`() {
        givenRefundTarget()
        givenRefundSaved(balanceAfter = 1800)
        val saved = slot<ItemPurchaseRefund>()
        every { itemPurchaseRefundRepository.save(capture(saved)) } answers {
            saved.captured.apply { ReflectionTestUtils.setField(this, "id", refundId) }
        }

        val response = createRefund(request(listOf(1L, 2L)))

        assertEquals(refundId, response.itemPurchaseRefundId)
        assertEquals(800, response.coinQuantity)
        assertEquals(1800, response.balance)
        assertEquals(adminId, saved.captured.adminId)
        assertEquals("실수 구매", saved.captured.reason)
        verifyOrder {
            coinService.lockCoin(userId)
            itemPurchaseProductRepository.findByIdOrNull(itemPurchaseProductId)
            itemPurchaseRefundRepository.save(any())
            coinService.increaseBalance(coin, 800, CoinHistoryCategory.ITEM_PURCHASE_REFUND, refundId)
        }
    }

    @Test
    fun `같은 구성품을 두 번 보내도 한 번만 기록한다`() {
        givenRefundTarget(lines = listOf(line(1L, 800)))
        givenRefundSaved()
        val lines = slot<List<ItemPurchaseRefundLine>>()
        every { itemPurchaseRefundLineRepository.saveAll(capture(lines)) } answers { lines.captured }

        createRefund(request(listOf(1L, 1L)))

        assertEquals(listOf(1L), lines.captured.map { it.itemPurchaseLineId })
    }

    @Test
    fun `돌려줄 코인이 0이면 예외로 환불해도 코인 내역을 남기지 않고 지금 잔액을 준다`() {
        givenRefundTarget(purchasePrice = 500, balance = 1000)
        givenRefundSaved()

        val response = createRefund(request(listOf(2L), expectedCoinQuantity = 0, isPolicyException = true))

        assertEquals(0, response.coinQuantity)
        assertEquals(1000, response.balance)
        verify(exactly = 0) { coinService.increaseBalance(any(), any(), any(), any()) }
    }

    @Test
    fun `정책에 어긋나는데 예외로 표시하지 않으면 아무것도 저장하지 않고 거부한다`() {
        val equipped = ownedRoomItem(1L, firstEquippedAt = now.minusSeconds(60), isEquipped = true)
        givenRefundTarget(ownedRoomItems = listOf(equipped))

        assertBusinessException(AdminErrorCode.REFUND_POLICY_EXCEPTION_REQUIRED) {
            createRefund(request(listOf(1L, 2L)))
        }

        assertNull(equipped.revokedAt)
        verify(exactly = 0) { itemPurchaseRefundRepository.save(any()) }
        verify(exactly = 0) { coinService.increaseBalance(any(), any(), any(), any()) }
    }

    @Test
    fun `돌려줄 코인이 화면에서 본 금액과 다르면 예외로 표시해도 아무것도 저장하지 않고 거부한다`() {
        val owned = ownedRoomItem(1L)
        givenRefundTarget(ownedRoomItems = listOf(owned))

        assertBusinessException(AdminErrorCode.REFUND_COIN_QUANTITY_CHANGED) {
            createRefund(request(listOf(1L, 2L), expectedCoinQuantity = 300, isPolicyException = true))
        }

        assertNull(owned.revokedAt)
        verify(exactly = 0) { itemPurchaseRefundRepository.save(any()) }
        verify(exactly = 0) { itemPurchaseRefundLineRepository.saveAll(any<List<ItemPurchaseRefundLine>>()) }
        verify(exactly = 0) { coinService.increaseBalance(any(), any(), any(), any()) }
    }

    @Test
    fun `금액도 바뀌고 정책에도 어긋나면 바뀐 금액을 먼저 알린다`() {
        val equipped = ownedRoomItem(1L, firstEquippedAt = now.minusSeconds(60))
        givenRefundTarget(ownedRoomItems = listOf(equipped))

        assertBusinessException(AdminErrorCode.REFUND_COIN_QUANTITY_CHANGED) {
            createRefund(request(listOf(1L, 2L), expectedCoinQuantity = 300))
        }
    }

    @Test
    fun `이 구매 상품의 구성품이 아닌 것이 있으면 거부한다`() {
        givenRefundTarget()

        assertBusinessException(AdminErrorCode.ITEM_PURCHASE_LINE_NOT_FOUND) {
            createRefund(request(listOf(1L, 99L)))
        }
    }

    @Test
    fun `이미 환불한 구성품이 있으면 거부한다`() {
        givenRefundTarget(refundedLineIds = listOf(1L), refundedCoinQuantity = 500)

        assertBusinessException(AdminErrorCode.ALREADY_REFUNDED) {
            createRefund(request(listOf(1L)))
        }
        verify(exactly = 0) { itemPurchaseRefundRepository.save(any()) }
    }

    @Test
    fun `받은 구성품은 보유 아이템을 회수하고 장착을 풀며 우편 아이템은 찾지 않는다`() {
        val owned = ownedRoomItem(1L, firstEquippedAt = now.minusSeconds(60), isEquipped = true)
        givenRefundTarget(lines = listOf(line(1L, 800)), ownedRoomItems = listOf(owned))
        givenRefundSaved()

        createRefund(request(listOf(1L), isPolicyException = true))

        assertEquals(now, owned.revokedAt)
        assertFalse(owned.isEquipped)
        assertEquals(now.minusSeconds(60), owned.firstEquippedAt)
        verify(exactly = 1) { mailItemRepository.findByItemPurchaseLineIdIn(emptySet()) }
    }

    @Test
    fun `받기 전 구성품은 우편 아이템을 회수하고 다른 아이템이 남으면 우편을 숨기지 않는다`() {
        val mailItem = MailItem(mailId = 50L, itemId = 1L, quantity = 1, itemPurchaseLineId = 1L)
        givenRefundTarget()
        givenRefundSaved()
        every { mailItemRepository.findByItemPurchaseLineIdIn(setOf(1L)) } returns listOf(mailItem)
        every { mailItemRepository.existsByMailIdAndRevokedAtIsNull(50L) } returns true

        createRefund(request(listOf(1L), expectedCoinQuantity = 500))

        assertEquals(now, mailItem.revokedAt)
        verify(exactly = 1) { mailRepository.findAllById(emptyList()) }
    }

    @Test
    fun `우편에 남은 아이템이 없으면 안 받은 우편도 숨긴다`() {
        val mailItem = MailItem(mailId = 50L, itemId = 1L, quantity = 1, itemPurchaseLineId = 1L)
        val mail = Mail(userId = userId, category = MailCategory.ITEM, title = "상품", content = "상점 구매")
        givenRefundTarget(lines = listOf(line(1L, 800)))
        givenRefundSaved()
        every { mailItemRepository.findByItemPurchaseLineIdIn(setOf(1L)) } returns listOf(mailItem)
        every { mailItemRepository.existsByMailIdAndRevokedAtIsNull(50L) } returns false
        every { mailRepository.findAllById(listOf(50L)) } returns listOf(mail)

        createRefund(request(listOf(1L)))

        assertEquals(now, mail.deletedAt)
    }
}

package com.dandi.nyummy.admin.service

import com.dandi.nyummy.item.entity.Item
import com.dandi.nyummy.item.repository.ItemRepository
import com.dandi.nyummy.room.entity.OwnedRoomItem
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import com.dandi.nyummy.shop.entity.ItemPurchase
import com.dandi.nyummy.shop.entity.ItemPurchaseLine
import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import com.dandi.nyummy.shop.entity.ItemPurchaseRefund
import com.dandi.nyummy.shop.entity.ItemPurchaseRefundLine
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.enum.ItemPurchaseLineStatus
import com.dandi.nyummy.shop.repository.ItemPurchaseLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRefundLineRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRefundRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminItemPurchaseServiceTest {

    private val userId = 1L
    private val pageable = PageRequest.of(0, SIZE)

    private val itemPurchaseRepository = mockk<ItemPurchaseRepository>()
    private val itemPurchaseProductRepository = mockk<ItemPurchaseProductRepository>()
    private val itemPurchaseLineRepository = mockk<ItemPurchaseLineRepository>()
    private val itemPurchaseRefundRepository = mockk<ItemPurchaseRefundRepository>()
    private val itemPurchaseRefundLineRepository = mockk<ItemPurchaseRefundLineRepository>()
    private val ownedRoomItemRepository = mockk<OwnedRoomItemRepository>()
    private val productRepository = mockk<ProductRepository>()
    private val itemRepository = mockk<ItemRepository>()

    private val service = AdminItemPurchaseService(
        itemPurchaseRepository = itemPurchaseRepository,
        itemPurchaseProductRepository = itemPurchaseProductRepository,
        itemPurchaseLineRepository = itemPurchaseLineRepository,
        itemPurchaseRefundRepository = itemPurchaseRefundRepository,
        itemPurchaseRefundLineRepository = itemPurchaseRefundLineRepository,
        ownedRoomItemRepository = ownedRoomItemRepository,
        productRepository = productRepository,
        itemRepository = itemRepository,
    )

    private companion object {
        const val SIZE = 20
        const val PRODUCT_ID = 100L
        const val ITEM_ID = 200L
        const val CURSOR = 50L
    }

    private fun itemPurchase(id: Long) = ItemPurchase(userId, purchasePrice = 800, idempotencyKey = "key-$id").apply {
        ReflectionTestUtils.setField(this, "id", id)
    }

    private fun itemPurchaseProduct(id: Long, itemPurchaseId: Long) =
        ItemPurchaseProduct(itemPurchaseId, PRODUCT_ID, null, listPrice = 1000, purchasePrice = 800).apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    private fun line(id: Long, itemPurchaseProductId: Long) =
        ItemPurchaseLine(itemPurchaseProductId, ITEM_ID, listPrice = 500).apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    private fun refund(id: Long, itemPurchaseProductId: Long) =
        ItemPurchaseRefund(itemPurchaseProductId, adminId = 2L, coinQuantity = 300, reason = "실수 구매").apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    private fun ownedRoomItem(lineId: Long, isEquipped: Boolean = false) =
        OwnedRoomItem(userId, ITEM_ID, lineId).apply { this.isEquipped = isEquipped }

    private fun givenItemPurchases(
        itemPurchases: List<ItemPurchase>,
        hasNext: Boolean = false,
        cursor: Long = Long.MAX_VALUE,
    ) {
        every {
            itemPurchaseRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor, pageable)
        } returns SliceImpl(itemPurchases, pageable, hasNext)
    }

    private fun givenDetails(
        products: List<ItemPurchaseProduct> = listOf(itemPurchaseProduct(10L, 1L)),
        lines: List<ItemPurchaseLine> = listOf(line(1000L, 10L)),
        refunds: List<ItemPurchaseRefund> = emptyList(),
        refundedLineIds: List<Long> = emptyList(),
        ownedRoomItems: List<OwnedRoomItem> = emptyList(),
    ) {
        every { itemPurchaseProductRepository.findByItemPurchaseIdInOrderByIdAsc(any()) } returns products
        every { itemPurchaseLineRepository.findByItemPurchaseProductIdInOrderByIdAsc(any()) } returns lines
        every { itemPurchaseRefundRepository.findByItemPurchaseProductIdInOrderByIdAsc(any()) } returns refunds
        every { itemPurchaseRefundLineRepository.findByItemPurchaseLineIdIn(any()) } returns
            refundedLineIds.map { ItemPurchaseRefundLine(30L, it) }
        every { ownedRoomItemRepository.findByItemPurchaseLineIdIn(any()) } returns ownedRoomItems
        every { productRepository.findAllById(any()) } returns listOf(
            Product(
                category = RoomItemCategory.BED,
                name = "세트",
                representativeItemId = ITEM_ID,
                price = 800,
                isLimited = false,
                saleStartsAt = Instant.EPOCH,
            ).apply { ReflectionTestUtils.setField(this, "id", PRODUCT_ID) },
        )
        every { itemRepository.findAllById(any()) } returns listOf(
            Item(name = "트리", price = 500).apply { ReflectionTestUtils.setField(this, "id", ITEM_ID) },
        )
    }

    private fun getSingleLine() = service.getItemPurchases(userId, null, SIZE)
        .itemPurchases.single().products.single().lines.single()

    @Test
    fun `구매가 없으면 빈 목록을 주고 상세 쿼리를 하지 않는다`() {
        givenItemPurchases(emptyList())

        val response = service.getItemPurchases(userId, null, SIZE)

        assertEquals(emptyList(), response.itemPurchases)
        assertNull(response.nextCursor)
        assertFalse(response.hasNext)
        verify(exactly = 0) { itemPurchaseProductRepository.findByItemPurchaseIdInOrderByIdAsc(any()) }
    }

    @Test
    fun `커서가 있으면 그 앞부터 조회하고 다음 묶음이 있으면 마지막 구매 id를 다음 커서로 준다`() {
        givenItemPurchases(listOf(itemPurchase(4L), itemPurchase(3L)), hasNext = true, cursor = CURSOR)
        givenDetails(products = emptyList(), lines = emptyList())

        val response = service.getItemPurchases(userId, CURSOR, SIZE)

        assertEquals(listOf(4L, 3L), response.itemPurchases.map { it.itemPurchaseId })
        assertEquals(3L, response.nextCursor)
        assertTrue(response.hasNext)
    }

    @Test
    fun `보유 아이템과 환불 기록이 없는 구성품은 받기 전이다`() {
        givenItemPurchases(listOf(itemPurchase(1L)))
        givenDetails()

        val line = getSingleLine()

        assertEquals(ItemPurchaseLineStatus.NOT_CLAIMED, line.status)
        assertFalse(line.isEquipped)
    }

    @Test
    fun `보유 아이템이 있는 구성품은 받은 상태이고 장착 여부를 함께 준다`() {
        givenItemPurchases(listOf(itemPurchase(1L)))
        givenDetails(ownedRoomItems = listOf(ownedRoomItem(1000L, isEquipped = true)))

        val line = getSingleLine()

        assertEquals(ItemPurchaseLineStatus.CLAIMED, line.status)
        assertTrue(line.isEquipped)
    }

    @Test
    fun `회수한 보유 아이템이 남아 있어도 환불 기록이 있으면 환불 상태다`() {
        givenItemPurchases(listOf(itemPurchase(1L)))
        givenDetails(
            refundedLineIds = listOf(1000L),
            ownedRoomItems = listOf(ownedRoomItem(1000L).apply { revoke(Instant.EPOCH) }),
        )

        val line = getSingleLine()

        assertEquals(ItemPurchaseLineStatus.REFUNDED, line.status)
        assertFalse(line.isEquipped)
    }

    @Test
    fun `구매 상품과 구성품과 환불을 각자의 부모 아래에 이름과 함께 묶는다`() {
        givenItemPurchases(listOf(itemPurchase(2L), itemPurchase(1L)))
        givenDetails(
            products = listOf(itemPurchaseProduct(10L, 1L), itemPurchaseProduct(20L, 2L)),
            lines = listOf(line(1000L, 10L), line(1001L, 10L), line(2000L, 20L)),
            refunds = listOf(refund(30L, 20L)),
        )

        val response = service.getItemPurchases(userId, null, SIZE)

        val (newest, oldest) = response.itemPurchases
        assertEquals(listOf(20L), newest.products.map { it.itemPurchaseProductId })
        assertEquals(listOf(2000L), newest.products.single().lines.map { it.itemPurchaseLineId })
        assertEquals(listOf(30L), newest.products.single().refunds.map { it.itemPurchaseRefundId })
        assertEquals(listOf(10L), oldest.products.map { it.itemPurchaseProductId })
        assertEquals(listOf(1000L, 1001L), oldest.products.single().lines.map { it.itemPurchaseLineId })
        assertEquals(emptyList(), oldest.products.single().refunds)
        assertEquals("세트", oldest.products.single().productName)
        assertEquals("트리", oldest.products.single().lines.first().itemName)
    }

    @Test
    fun `구매가 여러 개여도 상세 쿼리는 묶음으로 한 번씩만 한다`() {
        givenItemPurchases(listOf(itemPurchase(2L), itemPurchase(1L)))
        givenDetails(
            products = listOf(itemPurchaseProduct(10L, 1L), itemPurchaseProduct(20L, 2L)),
            lines = listOf(line(1000L, 10L), line(2000L, 20L)),
        )

        service.getItemPurchases(userId, null, SIZE)

        verify(exactly = 1) { itemPurchaseProductRepository.findByItemPurchaseIdInOrderByIdAsc(listOf(2L, 1L)) }
        verify(exactly = 1) { itemPurchaseLineRepository.findByItemPurchaseProductIdInOrderByIdAsc(listOf(10L, 20L)) }
        verify(exactly = 1) { itemPurchaseRefundRepository.findByItemPurchaseProductIdInOrderByIdAsc(listOf(10L, 20L)) }
        verify(exactly = 1) { itemPurchaseRefundLineRepository.findByItemPurchaseLineIdIn(listOf(1000L, 2000L)) }
        verify(exactly = 1) { ownedRoomItemRepository.findByItemPurchaseLineIdIn(listOf(1000L, 2000L)) }
    }
}

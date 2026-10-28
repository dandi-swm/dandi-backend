package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.AdminProperties
import com.dandi.nyummy.admin.dto.CreatePromotionRequest
import com.dandi.nyummy.admin.dto.UpdatePromotionProductsRequest
import com.dandi.nyummy.admin.dto.UpdatePromotionRequest
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.enum.PromotionCategory
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import com.dandi.nyummy.shop.repository.PromotionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdminPromotionServiceTest {

    private val startsAt = Instant.parse("2026-11-01T00:00:00Z")
    private val endsAt = Instant.parse("2026-11-08T00:00:00Z")
    private val promotionId = 3L

    private val promotionRepository = mockk<PromotionRepository>()
    private val promotionProductRepository = mockk<PromotionProductRepository>()
    private val productRepository = mockk<ProductRepository>()

    private val adminPromotionService = AdminPromotionService(
        promotionRepository = promotionRepository,
        promotionProductRepository = promotionProductRepository,
        productRepository = productRepository,
        adminProperties = AdminProperties(cdnBaseUrl = "https://cdn.test.com"),
    )

    private val products = listOf(
        product(id = 101, representativeItemId = 11, price = 650),
        product(id = 102, representativeItemId = 21, price = 333),
        product(id = 103, representativeItemId = 22, price = 1000),
    ).associateBy { it.id }

    private val savedPromotionProducts = slot<List<PromotionProduct>>()

    init {
        every { productRepository.findAllById(any()) } answers {
            firstArg<Iterable<Long>>().mapNotNull { products[it] }
        }
        every { promotionRepository.save(any()) } answers {
            firstArg<Promotion>().also { ReflectionTestUtils.setField(it, "id", promotionId) }
        }
        every { promotionProductRepository.saveAll(capture(savedPromotionProducts)) } answers { firstArg() }
        every { promotionProductRepository.deleteByPromotionId(promotionId) } returns 2
    }

    private fun product(id: Long, representativeItemId: Long, price: Int) = Product(
        category = RoomItemCategory.ETC,
        name = "상품 $id",
        representativeItemId = representativeItemId,
        price = price,
        isLimited = false,
        saleStartsAt = startsAt,
    ).also { ReflectionTestUtils.setField(it, "id", id) }

    private fun promotion(discountRate: Int? = 30) = Promotion(
        name = "신입 집사 환영",
        category = PromotionCategory.NEW_USER,
        discountRate = discountRate,
        startsAt = startsAt,
        endsAt = endsAt,
    ).also { ReflectionTestUtils.setField(it, "id", promotionId) }

    private fun createRequest(productIds: List<Long> = listOf(103, 101), endsAt: Instant? = this.endsAt) =
        CreatePromotionRequest(
            name = " 신입 집사 환영 ",
            category = PromotionCategory.NEW_USER,
            discountRate = 30,
            startsAt = startsAt,
            endsAt = endsAt,
            productIds = productIds,
        )

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun List<PromotionProduct>.toOrderedProductIds() = sortedBy { it.displayOrder }.map { it.productId }

    // createPromotion

    @Test
    fun `프로모션을 만들면 이름을 다듬고 요청 순서대로 진열한다`() {
        val response = adminPromotionService.createPromotion(createRequest())

        assertEquals("신입 집사 환영", response.name)
        assertEquals(listOf(103L, 101L), savedPromotionProducts.captured.toOrderedProductIds())
        assertEquals(listOf(103L, 101L), response.products.map { it.productId })
    }

    @Test
    fun `진열 상품마다 이 프로모션 할인율로 계산한 결제가를 준다`() {
        val response = adminPromotionService.createPromotion(createRequest(productIds = listOf(101, 102)))

        assertEquals(listOf(650 to 455, 333 to 233), response.products.map { it.listPrice to it.purchasePrice })
        assertEquals("https://cdn.test.com/items/11.png", response.products[0].imageUrl)
    }

    @Test
    fun `같은 상품을 두 번 넣으면 거부하고 저장하지 않는다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminPromotionService.createPromotion(createRequest(productIds = listOf(101, 101)))
        }
        verify(exactly = 0) { promotionRepository.save(any()) }
    }

    @Test
    fun `없는 상품이 있으면 거부하고 저장하지 않는다`() {
        assertBusinessException(AdminErrorCode.PRODUCT_NOT_FOUND) {
            adminPromotionService.createPromotion(createRequest(productIds = listOf(101, 999)))
        }
        verify(exactly = 0) { promotionRepository.save(any()) }
    }

    @Test
    fun `종료 시각이 시작 시각보다 앞이면 거부하고 저장하지 않는다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            adminPromotionService.createPromotion(createRequest(endsAt = startsAt.minusSeconds(1)))
        }
        verify(exactly = 0) { promotionRepository.save(any()) }
    }

    // updatePromotion

    @Test
    fun `프로모션을 수정하면 할인율과 기간을 보낸 값으로 바꾸고 진열 상품 결제가를 새 할인율로 준다`() {
        val promotion = promotion(discountRate = 30)
        every { promotionRepository.findByIdOrNull(promotionId) } returns promotion
        every { promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(listOf(promotionId)) } returns
            listOf(PromotionProduct(promotionId, 101, 0))

        val response = adminPromotionService.updatePromotion(
            promotionId,
            UpdatePromotionRequest(name = "새 이름", discountRate = null, startsAt = startsAt, endsAt = null),
        )

        assertNull(promotion.discountRate)
        assertNull(promotion.endsAt)
        assertEquals(650, response.products.single().purchasePrice)
    }

    @Test
    fun `없는 프로모션을 수정하면 거부한다`() {
        every { promotionRepository.findByIdOrNull(promotionId) } returns null

        assertBusinessException(AdminErrorCode.PROMOTION_NOT_FOUND) {
            adminPromotionService.updatePromotion(
                promotionId,
                UpdatePromotionRequest(name = "이름", discountRate = 10, startsAt = startsAt, endsAt = null),
            )
        }
    }

    // updatePromotionProducts

    @Test
    fun `진열 상품을 바꾸면 옛 진열을 지운 뒤 보낸 순서대로 다시 넣는다`() {
        every { promotionRepository.findByIdOrNull(promotionId) } returns promotion()

        val response = adminPromotionService.updatePromotionProducts(
            promotionId,
            UpdatePromotionProductsRequest(productIds = listOf(102, 103, 101)),
        )

        verifyOrder {
            promotionProductRepository.deleteByPromotionId(promotionId)
            promotionProductRepository.saveAll(any<List<PromotionProduct>>())
        }
        assertEquals(listOf(102L, 103L, 101L), savedPromotionProducts.captured.toOrderedProductIds())
        assertEquals(listOf(102L, 103L, 101L), response.products.map { it.productId })
    }

    @Test
    fun `진열 상품에 없는 상품이 있으면 옛 진열을 지우지 않는다`() {
        every { promotionRepository.findByIdOrNull(promotionId) } returns promotion()

        assertBusinessException(AdminErrorCode.PRODUCT_NOT_FOUND) {
            adminPromotionService.updatePromotionProducts(promotionId, UpdatePromotionProductsRequest(listOf(999)))
        }
        verify(exactly = 0) { promotionProductRepository.deleteByPromotionId(any()) }
    }

    @Test
    fun `없는 프로모션의 진열 상품을 바꾸면 거부한다`() {
        every { promotionRepository.findByIdOrNull(promotionId) } returns null

        assertBusinessException(AdminErrorCode.PROMOTION_NOT_FOUND) {
            adminPromotionService.updatePromotionProducts(promotionId, UpdatePromotionProductsRequest(listOf(101)))
        }
    }

    // getPromotions

    @Test
    fun `프로모션 목록은 프로모션마다 자기 진열 상품을 진열 순서대로 붙인다`() {
        val other = Promotion(
            name = "오늘의 할인",
            category = PromotionCategory.DAILY,
            discountRate = 10,
            startsAt = startsAt,
        ).also { ReflectionTestUtils.setField(it, "id", 4L) }
        every { promotionRepository.findAll(any<Sort>()) } returns
            listOf(other, promotion())
        every { promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(listOf(4L, promotionId)) } returns
            listOf(
                PromotionProduct(4L, 103, 0),
                PromotionProduct(promotionId, 102, 0),
                PromotionProduct(4L, 101, 1),
                PromotionProduct(promotionId, 101, 1),
            )

        val response = adminPromotionService.getPromotions()

        assertEquals(listOf(4L, promotionId), response.promotions.map { it.promotionId })
        assertEquals(listOf(103L, 101L), response.promotions[0].products.map { it.productId })
        assertEquals(listOf(900, 585), response.promotions[0].products.map { it.purchasePrice })
        assertEquals(listOf(102L, 101L), response.promotions[1].products.map { it.productId })
    }
}

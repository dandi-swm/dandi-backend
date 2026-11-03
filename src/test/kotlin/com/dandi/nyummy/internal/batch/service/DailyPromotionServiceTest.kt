package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.config.ShopProperties
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
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 시각은 모두 KST다. 11/04 23:50에 돌면 11/05 프로모션을 만든다.
 */
class DailyPromotionServiceTest {

    private val startsAt = Instant.parse("2026-11-05T00:00:00+09:00")
    private val endsAt = Instant.parse("2026-11-06T00:00:00+09:00")

    private val promotionRepository = mockk<PromotionRepository>()
    private val promotionProductRepository = mockk<PromotionProductRepository>()
    private val productRepository = mockk<ProductRepository>()

    private val promotionSlot = slot<Promotion>()
    private val promotionProductsSlot = slot<List<PromotionProduct>>()

    private val service = DailyPromotionService(
        promotionRepository = promotionRepository,
        promotionProductRepository = promotionProductRepository,
        productRepository = productRepository,
        shopProperties = ShopProperties(dailyDiscountRate = 5),
        clock = Clock.fixed(Instant.parse("2026-11-04T23:50:00+09:00"), ZoneOffset.UTC),
        random = Random(0),
    )

    init {
        every { promotionRepository.existsPromotionInPeriod(any(), any(), any()) } returns false
        every { promotionRepository.save(capture(promotionSlot)) } answers { firstArg() }
        every { promotionProductRepository.saveAll(capture(promotionProductsSlot)) } answers { firstArg() }
    }

    private fun product(
        id: Long,
        category: RoomItemCategory = RoomItemCategory.BED,
        isLimited: Boolean = false,
        saleEndsAt: Instant? = null,
    ) = Product(
        category = category,
        name = "상품 $id",
        representativeItemId = id * 10,
        price = 500,
        isLimited = isLimited,
        saleStartsAt = Instant.parse("2026-01-01T00:00:00Z"),
        saleEndsAt = saleEndsAt,
    ).also { ReflectionTestUtils.setField(it, "id", id) }

    private fun givenProducts(vararg products: Product) {
        every { productRepository.findOnSaleProducts(any()) } returns products.toList()
    }

    @Test
    fun `날짜를 주지 않으면 내일 하루치 프로모션을 만든다`() {
        givenProducts(product(id = 1))

        service.createDailyPromotion()

        val promotion = promotionSlot.captured
        assertEquals("11/05 오늘의 할인", promotion.name)
        assertEquals(PromotionCategory.DAILY, promotion.category)
        assertEquals(5, promotion.discountRate)
        assertEquals(startsAt, promotion.startsAt)
        assertEquals(endsAt, promotion.endsAt)
        verify { promotionRepository.existsPromotionInPeriod(PromotionCategory.DAILY, startsAt, endsAt) }
        verify { productRepository.findOnSaleProducts(startsAt) }
    }

    @Test
    fun `날짜를 주면 그 날짜로 만든다`() {
        givenProducts(product(id = 1))

        service.createDailyPromotion(LocalDate.of(2026, 11, 20))

        assertEquals("11/20 오늘의 할인", promotionSlot.captured.name)
        assertEquals(Instant.parse("2026-11-20T00:00:00+09:00"), promotionSlot.captured.startsAt)
        assertEquals(Instant.parse("2026-11-21T00:00:00+09:00"), promotionSlot.captured.endsAt)
    }

    @Test
    fun `고른 상품 3개를 진열 순서대로 저장한다`() {
        givenProducts(product(id = 1), product(id = 2), product(id = 3), product(id = 4))

        service.createDailyPromotion()

        val promotionProducts = promotionProductsSlot.captured
        assertEquals(3, promotionProducts.map { it.productId }.toSet().size)
        assertEquals(listOf(0, 1, 2), promotionProducts.map { it.displayOrder })
    }

    @Test
    fun `그날과 겹치는 프로모션이 있으면 만들지 않는다`() {
        every { promotionRepository.existsPromotionInPeriod(any(), any(), any()) } returns true

        service.createDailyPromotion()

        verify(exactly = 0) { promotionRepository.save(any()) }
        verify(exactly = 0) { promotionProductRepository.saveAll(any<List<PromotionProduct>>()) }
    }

    @Test
    fun `방 배경과 기간 한정, 그날 중에 판매가 끝나는 상품은 고르지 않는다`() {
        givenProducts(
            product(id = 1, category = RoomItemCategory.BACKGROUND),
            product(id = 2, isLimited = true),
            product(id = 3, saleEndsAt = endsAt.minusSeconds(1)),
            product(id = 4, saleEndsAt = endsAt),
        )

        service.createDailyPromotion()

        // 판매가 그날 자정에 끝나는 상품은 그날 내내 판매 중이다
        assertEquals(listOf(4L), promotionProductsSlot.captured.map { it.productId })
    }

    @Test
    fun `고를 상품이 없으면 만들지 않는다`() {
        givenProducts(product(id = 1, category = RoomItemCategory.BACKGROUND))

        service.createDailyPromotion()

        verify(exactly = 0) { promotionRepository.save(any()) }
    }
}

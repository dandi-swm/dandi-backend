package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.dto.PurchasedProductCountResult
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

class ProductSalesCountServiceTest {

    private val now = Instant.parse("2026-11-03T14:40:00Z")

    private val productRepository = mockk<ProductRepository>()
    private val itemPurchaseProductRepository = mockk<ItemPurchaseProductRepository>()

    private val service = ProductSalesCountService(
        productRepository = productRepository,
        itemPurchaseProductRepository = itemPurchaseProductRepository,
        shopProperties = ShopProperties(salesCountTimeToLive = Duration.ofDays(30)),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    init {
        every { productRepository.updateSalesCount(any(), any()) } returns 1
    }

    private fun product(id: Long, salesCount: Int) = Product(
        category = RoomItemCategory.BED,
        name = "상품 $id",
        representativeItemId = id * 10,
        price = 500,
        isLimited = false,
        saleStartsAt = now,
    ).also {
        ReflectionTestUtils.setField(it, "id", id)
        it.salesCount = salesCount
    }

    private fun givenProducts(vararg products: Product) {
        every { productRepository.findAll() } returns products.toList()
    }

    private fun givenSalesCounts(vararg salesCounts: Pair<Long, Long>) {
        every { itemPurchaseProductRepository.findSalesCounts(any()) } returns
            salesCounts.map { (productId, count) -> PurchasedProductCountResult(productId, count) }
    }

    @Test
    fun `지금부터 설정한 기간 전까지의 구매를 센다`() {
        givenProducts()
        givenSalesCounts()

        service.updateSalesCounts()

        verify { itemPurchaseProductRepository.findSalesCounts(now.minus(Duration.ofDays(30))) }
    }

    @Test
    fun `판매량이 바뀐 상품만 고친다`() {
        givenProducts(product(id = 1, salesCount = 3), product(id = 2, salesCount = 5))
        givenSalesCounts(1L to 4L, 2L to 5L)

        service.updateSalesCounts()

        verify(exactly = 1) { productRepository.updateSalesCount(1, 4) }
        verify(exactly = 0) { productRepository.updateSalesCount(2, any()) }
    }

    @Test
    fun `기간 안에 팔리지 않은 상품은 0으로 내린다`() {
        givenProducts(product(id = 1, salesCount = 3))
        givenSalesCounts()

        service.updateSalesCounts()

        verify(exactly = 1) { productRepository.updateSalesCount(1, 0) }
    }

    @Test
    fun `팔린 적 없고 판매량도 0인 상품은 고치지 않는다`() {
        givenProducts(product(id = 1, salesCount = 0))
        givenSalesCounts()

        service.updateSalesCounts()

        verify(exactly = 0) { productRepository.updateSalesCount(any(), any()) }
    }
}

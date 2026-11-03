package com.dandi.nyummy.shop.calculator

import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.entity.Product
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyProductCalculatorTest {

    private val seeds = 0 until 200

    private fun product(id: Long, salesCount: Int) = Product(
        category = RoomItemCategory.BED,
        name = "상품 $id",
        representativeItemId = id * 10,
        price = 500,
        isLimited = false,
        saleStartsAt = Instant.parse("2026-01-01T00:00:00Z"),
    ).also {
        ReflectionTestUtils.setField(it, "id", id)
        it.salesCount = salesCount
    }

    // id가 곧 판매량이라 id 1~10이 꼴찌 10개다
    private fun createCandidates(count: Int = 46) = (1L..count).map { product(id = it, salesCount = it.toInt()) }

    @Test
    fun `후보가 없으면 아무것도 고르지 않는다`() {
        assertEquals(emptyList(), pickDailyProducts(emptyList(), count = 3, random = Random(0)))
    }

    @Test
    fun `후보가 개수보다 적으면 후보 전체를 고른다`() {
        val candidates = createCandidates(count = 2)

        val products = pickDailyProducts(candidates, count = 3, random = Random(0))

        assertEquals(candidates.toSet(), products.toSet())
    }

    @Test
    fun `겹치지 않게 개수만큼 고르고 꼴찌 10개에서 하나 이상 나온다`() {
        val candidates = createCandidates()

        seeds.forEach { seed ->
            val productIds = pickDailyProducts(candidates, count = 3, random = Random(seed)).map { it.id }

            assertEquals(3, productIds.toSet().size)
            assertTrue(productIds.any { it <= 10 }, "꼴찌 10개에서 나오지 않았습니다: $productIds")
        }
    }

    @Test
    fun `판매량이 낮은 상품 몫은 꼴찌 10개 안에서 고루 나온다`() {
        val candidates = createCandidates()

        // 1개만 고르면 판매량이 낮은 상품 몫만 남는다
        val productIds = seeds.map { seed ->
            pickDailyProducts(candidates, count = 1, random = Random(seed)).single().id
        }.toSet()

        assertEquals((1L..10L).toSet(), productIds)
    }

    @Test
    fun `판매량이 모두 같으면 꼴찌 10개가 늘 같은 상품으로 정해지지 않는다`() {
        val candidates = (1L..46L).map { product(id = it, salesCount = 0) }

        val productIds = seeds.map { seed ->
            pickDailyProducts(candidates, count = 1, random = Random(seed)).single().id
        }.toSet()

        assertTrue(productIds.size > 10, "꼴찌 칸에서 나온 상품: $productIds")
    }

    @Test
    fun `나머지는 전체에서 고르므로 판매량이 높은 상품도 나온다`() {
        val candidates = createCandidates()

        val productIds = seeds.flatMap { seed ->
            pickDailyProducts(candidates, count = 3, random = Random(seed)).map { it.id }
        }.toSet()

        assertTrue(productIds.any { it > 10 })
    }

    @Test
    fun `판매량이 낮은 상품이 늘 같은 칸에 오지 않는다`() {
        val candidates = createCandidates()

        val lastProductIds = seeds.map { seed ->
            pickDailyProducts(candidates, count = 3, random = Random(seed)).last().id
        }

        assertTrue(lastProductIds.any { it > 10 }, "마지막 칸이 늘 꼴찌 10개입니다")
    }
}

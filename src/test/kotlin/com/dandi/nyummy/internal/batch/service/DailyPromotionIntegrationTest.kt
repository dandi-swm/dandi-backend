package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.item.entity.Item
import com.dandi.nyummy.item.repository.ItemRepository
import com.dandi.nyummy.room.entity.RoomItem
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.repository.RoomItemRepository
import com.dandi.nyummy.shop.dto.ItemListComponentResponse
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ProductItem
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.enum.PromotionCategory
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import com.dandi.nyummy.shop.repository.PromotionRepository
import com.dandi.nyummy.shop.service.ShopService
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 오늘의 할인 생성을 실제 MySQL에서 돌린다. 기간 겹침 쿼리와, 만든 프로모션이 추천 탭에 나오는지는 mock으로 확인할 수 없다.
 *
 * DB를 함께 쓰므로 테스트마다 다른 날짜를 쓰고, 겹침 쿼리 테스트는 다른 테스트에 걸리지 않게 GENERAL로 확인한다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class DailyPromotionIntegrationTest @Autowired constructor(
    private val dailyPromotionService: DailyPromotionService,
    private val shopService: ShopService,
    private val promotionRepository: PromotionRepository,
    private val promotionProductRepository: PromotionProductRepository,
    private val productRepository: ProductRepository,
    private val productItemRepository: ProductItemRepository,
    private val itemRepository: ItemRepository,
    private val roomItemRepository: RoomItemRepository,
    private val userRepository: UserRepository,
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

        private val ZONE = ZoneId.of("Asia/Seoul")
    }

    @Test
    fun `하루치 프로모션과 상품 3개를 저장하고 다시 불러도 또 만들지 않는다`() {
        repeat(4) { createProduct() }
        val date = LocalDate.of(2030, 1, 10)

        dailyPromotionService.createDailyPromotion(date)
        dailyPromotionService.createDailyPromotion(date)

        val promotions = findDailyPromotions(date)
        assertEquals(1, promotions.size)
        assertEquals(5, promotions.single().discountRate)
        assertEquals(
            listOf(0, 1, 2),
            promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(listOf(promotions.single().id))
                .map { it.displayOrder },
        )
    }

    @Test
    fun `앞날 프로모션이 자정에 끝나면 겹치지 않아 다음 날 것을 만든다`() {
        repeat(3) { createProduct() }
        val previousDate = LocalDate.of(2030, 2, 10)
        val date = previousDate.plusDays(1)

        dailyPromotionService.createDailyPromotion(previousDate)
        dailyPromotionService.createDailyPromotion(date)

        assertEquals(1, findDailyPromotions(previousDate).size)
        assertEquals(1, findDailyPromotions(date).size)
    }

    @Test
    fun `운영자가 그날 일부 시간에 만든 오늘의 할인이 있으면 만들지 않는다`() {
        repeat(3) { createProduct() }
        val date = LocalDate.of(2030, 3, 10)
        val startsAt = date.atStartOfDay(ZONE).toInstant().plus(Duration.ofHours(17))
        promotionRepository.save(createPromotion(PromotionCategory.DAILY, startsAt, startsAt.plus(Duration.ofHours(6))))

        dailyPromotionService.createDailyPromotion(date)

        assertEquals(listOf(startsAt), findDailyPromotions(date).map { it.startsAt })
    }

    @Test
    fun `기간이 조금이라도 겹치면 있다고 보고 경계에서 끝나거나 시작하면 없다고 본다`() {
        val startsAt = Instant.parse("2031-01-10T00:00:00Z")
        val endsAt = Instant.parse("2031-01-11T00:00:00Z")
        val hour = Duration.ofHours(1)

        fun exists() = promotionRepository.existsPromotionInPeriod(PromotionCategory.GENERAL, startsAt, endsAt)

        promotionRepository.save(createPromotion(PromotionCategory.GENERAL, startsAt.minus(hour), startsAt))
        promotionRepository.save(createPromotion(PromotionCategory.GENERAL, endsAt, endsAt.plus(hour)))
        assertFalse(exists(), "경계에서 끝나거나 시작하는 프로모션은 겹치지 않습니다")

        promotionRepository.save(createPromotion(PromotionCategory.GENERAL, startsAt.plus(hour), endsAt.minus(hour)))
        assertTrue(exists(), "기간 안의 프로모션은 겹칩니다")
    }

    @Test
    fun `종료일이 없는 프로모션은 시작한 뒤의 모든 날과 겹치고 다른 카테고리는 보지 않는다`() {
        val promotionStartsAt = Instant.parse("2032-01-10T00:00:00Z")
        promotionRepository.save(createPromotion(PromotionCategory.FIRST_PURCHASE, promotionStartsAt, null))

        fun exists(category: PromotionCategory, startsAt: Instant) = promotionRepository.existsPromotionInPeriod(
            category,
            startsAt,
            startsAt.plus(Duration.ofDays(1)),
        )

        assertTrue(exists(PromotionCategory.FIRST_PURCHASE, promotionStartsAt.plus(Duration.ofDays(30))))
        assertFalse(exists(PromotionCategory.FIRST_PURCHASE, promotionStartsAt.minus(Duration.ofDays(1))))
        assertFalse(exists(PromotionCategory.NEW_USER, promotionStartsAt.plus(Duration.ofDays(30))))
    }

    @Test
    fun `만든 프로모션의 상품이 추천 탭 오늘의 할인에 할인가로 나온다`() {
        repeat(3) { createProduct() }
        val userId = userRepository.save(
            User(provider = AuthProvider.EMAIL, email = "${UUID.randomUUID()}@nyummy.co.kr"),
        ).id
        val today = LocalDate.now(clock.withZone(ZONE))

        dailyPromotionService.createDailyPromotion(today)

        val page = shopService.getRecommendPage(userId, null)
        val items = page.sduiComponents.filterIsInstance<ItemListComponentResponse>().single().content.items
        assertEquals(3, items.size)
        items.forEach { item ->
            val price = item.price.joinToString("") { it.text.orEmpty() }
            assertTrue("950" in price, "1000코인의 5% 할인가가 아닙니다: $price")
        }
    }

    private fun findDailyPromotions(date: LocalDate): List<Promotion> {
        val startsAt = date.atStartOfDay(ZONE).toInstant()
        val endsAt = date.plusDays(1).atStartOfDay(ZONE).toInstant()
        return promotionRepository.findAll().filter {
            it.category == PromotionCategory.DAILY && !it.startsAt.isBefore(startsAt) && it.startsAt.isBefore(endsAt)
        }
    }

    private fun createPromotion(category: PromotionCategory, startsAt: Instant, endsAt: Instant?) = Promotion(
        name = "프로모션",
        category = category,
        startsAt = startsAt,
        endsAt = endsAt,
    )

    private fun createProduct(): Product {
        val item = itemRepository.save(Item(name = "아이템", price = 1000))
        roomItemRepository.save(RoomItem(itemId = item.id, category = RoomItemCategory.BED))
        val product = productRepository.save(
            Product(
                category = RoomItemCategory.BED,
                name = "상품",
                representativeItemId = item.id,
                price = 1000,
                isLimited = false,
                saleStartsAt = Instant.now(clock).minus(Duration.ofDays(1)),
            ),
        )
        productItemRepository.save(ProductItem(product.id, item.id, 1000))
        return product
    }
}

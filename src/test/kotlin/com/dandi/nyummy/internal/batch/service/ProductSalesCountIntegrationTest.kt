package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.item.entity.Item
import com.dandi.nyummy.item.repository.ItemRepository
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.entity.ItemPurchase
import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 판매량 집계와 갱신을 실제 MySQL에서 돌린다. JPQL 집계와, 트랜잭션 없는 서비스에서 부르는 UPDATE는 mock으로 확인할 수 없다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ProductSalesCountIntegrationTest @Autowired constructor(
    private val productSalesCountService: ProductSalesCountService,
    private val userRepository: UserRepository,
    private val itemRepository: ItemRepository,
    private val productRepository: ProductRepository,
    private val itemPurchaseRepository: ItemPurchaseRepository,
    private val itemPurchaseProductRepository: ItemPurchaseProductRepository,
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
    }

    @Test
    fun `기간 안의 구매 상품 행을 세어 판매량을 고치고 지난 구매는 세지 않는다`() {
        val userId = createUser()
        val product = createProduct()
        createItemPurchase(userId, listOf(product, product), now().minus(Duration.ofDays(29)))
        createItemPurchase(userId, listOf(product), now().minus(Duration.ofDays(1)))
        createItemPurchase(userId, listOf(product), now().minus(Duration.ofDays(31)))

        productSalesCountService.updateSalesCounts()

        assertEquals(3, getSalesCount(product))
    }

    @Test
    fun `기간이 지나 팔린 게 없어진 상품은 판매량이 0이 된다`() {
        val userId = createUser()
        val product = createProduct(salesCount = 5)
        createItemPurchase(userId, listOf(product), now().minus(Duration.ofDays(31)))

        productSalesCountService.updateSalesCounts()

        assertEquals(0, getSalesCount(product))
    }

    @Test
    fun `시작 시각에 산 구매는 세고 그 전에 산 구매는 세지 않는다`() {
        val userId = createUser()
        val includedProduct = createProduct()
        val excludedProduct = createProduct()
        val startsAt = now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(30))
        createItemPurchase(userId, listOf(includedProduct), startsAt)
        createItemPurchase(userId, listOf(excludedProduct), startsAt.minusSeconds(1))

        val salesCounts = itemPurchaseProductRepository.findSalesCounts(startsAt)
            .associate { it.productId to it.purchasedCount }

        assertEquals(1, salesCounts[includedProduct.id])
        assertEquals(null, salesCounts[excludedProduct.id])
    }

    private fun now(): Instant = Instant.now(clock)

    private fun createUser(): Long = userRepository.save(
        User(provider = AuthProvider.EMAIL, email = "${UUID.randomUUID()}@nyummy.co.kr"),
    ).id

    private fun createProduct(salesCount: Int = 0): Product {
        val item = itemRepository.save(Item(name = "아이템", price = 500))
        return productRepository.save(
            Product(
                category = RoomItemCategory.BED,
                name = "상품",
                representativeItemId = item.id,
                price = 500,
                isLimited = false,
                saleStartsAt = now().minus(Duration.ofDays(60)),
            ).apply { this.salesCount = salesCount },
        )
    }

    // 구매 시각은 저장할 때 정해지므로 저장한 뒤 바꾼다
    private fun createItemPurchase(userId: Long, products: List<Product>, createdAt: Instant) {
        val itemPurchase = itemPurchaseRepository.save(ItemPurchase(userId, 500, UUID.randomUUID().toString()))
        products.forEach {
            itemPurchaseProductRepository.save(ItemPurchaseProduct(itemPurchase.id, it.id, null, 500, 500))
        }
        jdbcTemplate.update(
            "update item_purchases set created_at = ? where id = ?",
            Timestamp.from(createdAt),
            itemPurchase.id,
        )
    }

    private fun getSalesCount(product: Product): Int =
        assertNotNull(productRepository.findByIdOrNull(product.id)).salesCount
}

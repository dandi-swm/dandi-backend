package com.dandi.nyummy.shop.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.room.dto.OwnedRoomItemCountResult
import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.dto.HeroBannerComponentResponse
import com.dandi.nyummy.shop.dto.ItemListComponentResponse
import com.dandi.nyummy.shop.dto.RankingComponentResponse
import com.dandi.nyummy.shop.dto.SectionHeaderComponentResponse
import com.dandi.nyummy.shop.dto.ShopPageResponse
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ProductItem
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.entity.ShopBanner
import com.dandi.nyummy.shop.enum.BannerCornerStyle
import com.dandi.nyummy.shop.enum.PromotionCategory
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import com.dandi.nyummy.shop.repository.PromotionRepository
import com.dandi.nyummy.shop.repository.ShopBannerRepository
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopServiceTest {

    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val userId = 1L

    private val productRepository = mockk<ProductRepository>()
    private val productItemRepository = mockk<ProductItemRepository>()
    private val promotionRepository = mockk<PromotionRepository>()
    private val promotionProductRepository = mockk<PromotionProductRepository>()
    private val shopBannerRepository = mockk<ShopBannerRepository>()
    private val ownedRoomItemRepository = mockk<OwnedRoomItemRepository>()
    private val userRepository = mockk<UserRepository>()

    private val shopService = ShopService(
        productRepository = productRepository,
        productItemRepository = productItemRepository,
        promotionRepository = promotionRepository,
        promotionProductRepository = promotionProductRepository,
        shopBannerRepository = shopBannerRepository,
        ownedRoomItemRepository = ownedRoomItemRepository,
        userRepository = userRepository,
        shopProperties = ShopProperties(cdnBaseUrl = "https://cdn.test.com"),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    // 테스트마다 채우는 DB 상태
    private var products = listOf<Product>()
    private var productItems = listOf<ProductItem>()
    private var promotions = listOf<Promotion>()
    private var promotionProducts = listOf<PromotionProduct>()
    private var shopBanners = listOf<ShopBanner>()
    private var ownedCounts = listOf<OwnedRoomItemCountResult>()

    init {
        val user = User(provider = AuthProvider.EMAIL, email = "cat@test.com")
            .also { it.createdAt = now.minus(Duration.ofDays(30)) }
        every { userRepository.findByIdOrNull(userId) } returns user
        every { productRepository.findOnSaleProducts(now) } answers { products }
        every { productItemRepository.findByProductIdIn(any()) } answers {
            val productIds = firstArg<Collection<Long>>()
            productItems.filter { it.productId in productIds }
        }
        every { promotionRepository.findActivePromotions(now) } answers { promotions }
        every { promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(any()) } answers {
            val promotionIds = firstArg<Collection<Long>>()
            promotionProducts.filter { it.promotionId in promotionIds }.sortedBy { it.displayOrder }
        }
        every { shopBannerRepository.findActiveShopBanners(now) } answers { shopBanners }
        every { ownedRoomItemRepository.findOwnedRoomItemCounts(userId, any()) } answers { ownedCounts }
    }

    // 단품이면 구성품이 대표 아이템 하나다
    private fun product(
        id: Long,
        price: Int = 1000,
        category: RoomItemCategory = RoomItemCategory.BED,
        salesCount: Int = 0,
        saleStartsAt: Instant = now.minus(Duration.ofDays(30)),
    ) = Product(
        category = category,
        name = "상품 $id",
        representativeItemId = id * 10,
        price = price,
        isLimited = false,
        saleStartsAt = saleStartsAt,
    ).also {
        ReflectionTestUtils.setField(it, "id", id)
        it.salesCount = salesCount
    }

    private fun single(product: Product) = ProductItem(product.id, product.representativeItemId, product.price)

    private fun promotion(
        id: Long,
        category: PromotionCategory = PromotionCategory.GENERAL,
        discountRate: Int? = null,
        endsAt: Instant? = null,
    ) = Promotion(
        name = "프로모션 $id",
        category = category,
        discountRate = discountRate,
        startsAt = now.minus(Duration.ofDays(1)),
        endsAt = endsAt,
    ).also { ReflectionTestUtils.setField(it, "id", id) }

    private fun shopBanner(id: Long, promotionId: Long) = ShopBanner(
        promotionId = promotionId,
        imageKey = "shop-banners/$id.png",
        title = "배너 $id",
        titleColor = "contentPrimary",
        displayOrder = 0,
        startsAt = now.minus(Duration.ofDays(1)),
        adminId = 1,
    ).also { ReflectionTestUtils.setField(it, "id", id) }

    private fun getRecommendPage(shopLayoutVariant: String? = null) =
        shopService.getRecommendPage(userId, shopLayoutVariant)

    private inline fun <reified T> ShopPageResponse.componentOf(): T = sduiComponents.filterIsInstance<T>().single()

    @Test
    fun `배너도 상품도 없으면 빈 칸 목록을 준다`() {
        val page = getRecommendPage()

        assertEquals("RECOMMEND", page.pageKey)
        assertTrue(page.sduiComponents.isEmpty())
    }

    @Test
    fun `대상이 아닌 프로모션의 배너는 빼고 남은 배너를 둥근 모서리로 준다`() {
        // 가입한 지 30일이라 신입 집사 프로모션 대상이 아니다
        promotions = listOf(promotion(1, PromotionCategory.NEW_USER), promotion(2))
        shopBanners = listOf(shopBanner(11, promotionId = 1), shopBanner(12, promotionId = 2))

        val banners = getRecommendPage().componentOf<HeroBannerComponentResponse>().content.banners

        val banner = banners.single()
        assertEquals(12, banner.bannerId)
        assertEquals("https://cdn.test.com/shop-banners/12.png", banner.imageUrl)
        assertEquals(BannerCornerStyle.ROUNDED, banner.cornerStyle)
        assertEquals("https://link.nyummy.co.kr/shop/promotions/2", banner.action?.navigateLink)
    }

    @Test
    fun `배너 가격은 프로모션 상품의 정가 합과 결제가 합이다`() {
        val bed = product(101, price = 1000)
        val rug = product(102, price = 500, category = RoomItemCategory.RUG)
        products = listOf(bed, rug)
        productItems = listOf(single(bed), single(rug))
        promotions = listOf(promotion(2, discountRate = 30))
        promotionProducts = listOf(PromotionProduct(2, 101, 0), PromotionProduct(2, 102, 1))
        shopBanners = listOf(shopBanner(12, promotionId = 2))

        val price = getRecommendPage().componentOf<HeroBannerComponentResponse>().content.banners.single().price

        // 정가 1,500 → 결제가 700 + 350 = 1,050
        assertEquals(listOf("30%", null, "1,050 ", "1,500"), price?.map { it.text })
    }

    @Test
    fun `할인율이 없는 프로모션의 배너는 다른 프로모션 할인이 걸려 있어도 가격을 비운다`() {
        val bed = product(101, price = 1000)
        products = listOf(bed)
        productItems = listOf(single(bed))
        // 할로윈 한정처럼 할인 없이 묶은 프로모션(2)과, 같은 상품을 40% 할인하는 오늘의 할인(3)
        promotions = listOf(promotion(2), promotion(3, PromotionCategory.DAILY, discountRate = 40))
        promotionProducts = listOf(PromotionProduct(2, 101, 0), PromotionProduct(3, 101, 0))
        shopBanners = listOf(shopBanner(12, promotionId = 2))

        val banner = getRecommendPage().componentOf<HeroBannerComponentResponse>().content.banners.single()

        assertNull(banner.price)
    }

    @Test
    fun `오늘의 할인은 진열 순서대로 3개까지 주고 남은 시간은 시간 단위로 올린다`() {
        products = (101L..104L).map { product(it) }
        productItems = products.map { single(it) }
        promotions = listOf(promotion(3, PromotionCategory.DAILY, endsAt = now.plus(Duration.ofMinutes(70))))
        promotionProducts = listOf(104L, 103L, 102L, 101L).mapIndexed { index, productId ->
            PromotionProduct(3, productId, index)
        }

        val page = getRecommendPage()

        val items = page.componentOf<ItemListComponentResponse>().content.items
        assertEquals(listOf(104L, 103L, 102L), items.map { it.productId })
        val header = page.sduiComponents.filterIsInstance<SectionHeaderComponentResponse>().first().content
        assertEquals("2시간 후 바뀌어요", header.trailing?.content?.last()?.text)
    }

    @Test
    fun `오늘의 할인에 종료 시각이 없으면 남은 시간을 비운다`() {
        products = listOf(product(101))
        productItems = products.map { single(it) }
        promotions = listOf(promotion(3, PromotionCategory.DAILY))
        promotionProducts = listOf(PromotionProduct(3, 101, 0))

        val header = getRecommendPage().sduiComponents
            .filterIsInstance<SectionHeaderComponentResponse>()
            .first()
            .content

        assertEquals("오늘의 할인", header.title.single().text)
        assertNull(header.trailing)
    }

    @Test
    fun `인기는 인테리어 상품만 판매량순으로 4개 주고 판매량이 같으면 최신 상품이 앞이다`() {
        products = listOf(
            product(101, salesCount = 10),
            product(102, salesCount = 50),
            product(103, salesCount = 30),
            product(104, salesCount = 30),
            product(105, salesCount = 5),
            product(106, salesCount = 999, category = RoomItemCategory.BACKGROUND),
        )
        productItems = products.map { single(it) }

        val items = getRecommendPage().componentOf<RankingComponentResponse>().content.items

        assertEquals(listOf(102L, 104L, 103L, 101L), items.map { it.productId })
    }

    @Test
    fun `가격은 상품이 든 프로모션 중 가장 큰 할인율로 계산하고 할인 태그를 붙인다`() {
        products = listOf(product(101, price = 1000))
        productItems = products.map { single(it) }
        promotions = listOf(promotion(2, discountRate = 30), promotion(3, PromotionCategory.DAILY, discountRate = 40))
        promotionProducts = listOf(PromotionProduct(2, 101, 0), PromotionProduct(3, 101, 0))

        val item = getRecommendPage().componentOf<ItemListComponentResponse>().content.items.single()

        assertEquals(listOf("40%", null, "600 ", "1,000"), item.price.map { it.text })
        assertEquals("할인", item.tag?.text)
    }

    @Test
    fun `단품은 대표 아이템 보유 개수를, 세트는 0을 준다`() {
        val bed = product(101)
        val set = product(102, salesCount = 1)
        products = listOf(bed, set)
        productItems = listOf(single(bed), ProductItem(102, 1020, 600), ProductItem(102, 1021, 600))
        ownedCounts = listOf(OwnedRoomItemCountResult(itemId = 1010, ownedCount = 2))

        val items = getRecommendPage().componentOf<RankingComponentResponse>().content.items

        assertEquals(mapOf(101L to 2, 102L to 0), items.associate { it.productId to it.ownedCount })
    }

    @Test
    fun `판매를 시작한 지 7일이 안 된 상품에만 신규 태그를 붙인다`() {
        val newProduct = product(101, saleStartsAt = now.minus(Duration.ofDays(7)).plusSeconds(1))
        val oldProduct = product(102, saleStartsAt = now.minus(Duration.ofDays(7)))
        products = listOf(newProduct, oldProduct)
        productItems = products.map { single(it) }

        val items = getRecommendPage().componentOf<RankingComponentResponse>().content.items

        assertEquals(mapOf(102L to null, 101L to "신규"), items.associate { it.productId to it.tag?.text })
    }

    @Test
    fun `B 그룹은 인기를 오늘의 할인보다 먼저 준다`() {
        products = listOf(product(101))
        productItems = products.map { single(it) }
        promotions = listOf(promotion(3, PromotionCategory.DAILY))
        promotionProducts = listOf(PromotionProduct(3, 101, 0))

        val viewTypesA = getRecommendPage().sduiComponents.map { it.viewType }
        val viewTypesB = getRecommendPage(shopLayoutVariant = "B").sduiComponents.map { it.viewType }

        assertEquals(listOf("SectionHeader", "ItemList", "SectionHeader", "Ranking"), viewTypesA)
        assertEquals(listOf("SectionHeader", "Ranking", "SectionHeader", "ItemList"), viewTypesB)
    }
}

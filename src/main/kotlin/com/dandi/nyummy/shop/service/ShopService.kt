package com.dandi.nyummy.shop.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.room.repository.OwnedRoomItemRepository
import com.dandi.nyummy.shop.calculator.calculateMaxDiscountRates
import com.dandi.nyummy.shop.calculator.calculatePurchasePrice
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.dto.HeroBannerComponentResponse
import com.dandi.nyummy.shop.dto.HeroBannerContentResponse
import com.dandi.nyummy.shop.dto.ItemListComponentResponse
import com.dandi.nyummy.shop.dto.PromotionProductsResponse
import com.dandi.nyummy.shop.dto.RankingComponentResponse
import com.dandi.nyummy.shop.dto.RichTextResponse
import com.dandi.nyummy.shop.dto.SduiComponentResponse
import com.dandi.nyummy.shop.dto.SectionHeaderComponentResponse
import com.dandi.nyummy.shop.dto.SectionHeaderContentResponse
import com.dandi.nyummy.shop.dto.SectionHeaderTrailingResponse
import com.dandi.nyummy.shop.dto.ShopItemResponse
import com.dandi.nyummy.shop.dto.ShopItemsContentResponse
import com.dandi.nyummy.shop.dto.ShopItemsResponse
import com.dandi.nyummy.shop.dto.ShopMeResponse
import com.dandi.nyummy.shop.dto.ShopPageResponse
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.entity.ShopBanner
import com.dandi.nyummy.shop.enum.BannerCornerStyle
import com.dandi.nyummy.shop.enum.ProductSort
import com.dandi.nyummy.shop.enum.PromotionCategory
import com.dandi.nyummy.shop.enum.ShopCategory
import com.dandi.nyummy.shop.enum.ShopTab
import com.dandi.nyummy.shop.mapper.createNavigateAction
import com.dandi.nyummy.shop.mapper.createPriceText
import com.dandi.nyummy.shop.mapper.toShopBannerResponse
import com.dandi.nyummy.shop.mapper.toShopItemResponse
import com.dandi.nyummy.shop.repository.ProductItemRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import com.dandi.nyummy.shop.repository.PromotionRepository
import com.dandi.nyummy.shop.repository.ShopBannerRepository
import com.dandi.nyummy.shop.service.ShopStubData.StubProduct
import com.dandi.nyummy.shop.service.ShopStubData.toShopItemResponse
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 상점 추천 탭·상품 목록·프로모션 상품 목록·상단 바. 추천 탭과 프로모션 상품 목록은 DB의 상품·프로모션·배너로 응답하고,
 * 상품 목록과 상단 바는 아직 [ShopStubData]의 고정 데이터로 응답한다.
 */
@Service
class ShopService(
    private val clock: Clock,
    private val userRepository: UserRepository,
    private val promotionRepository: PromotionRepository,
    private val shopProperties: ShopProperties,
    private val promotionProductRepository: PromotionProductRepository,
    private val productRepository: ProductRepository,
    private val ownedRoomItemRepository: OwnedRoomItemRepository,
    private val productItemRepository: ProductItemRepository,
    private val shopBannerRepository: ShopBannerRepository,
) {

    companion object {
        private const val LAYOUT_B = "B"
        private const val DAILY_PRODUCT_COUNT = 3
        private const val POPULAR_PRODUCT_COUNT = 4
    }

    /**
     * 추천 탭 SDUI 칸 목록. A는 배너 → 오늘의 할인 → 인기, B는 배너 → 인기 → 오늘의 할인 순이다.
     * 배너가 없으면 배너 칸을, 상품이 없는 섹션은 위 제목 줄까지 같이 뺀다.
     *
     * - 배너: 노출 중이고, 알리는 프로모션이 진행 중이며 사용자가 대상인 배너를 노출 순서대로
     * - 오늘의 할인: 진행 중인 오늘의 할인 프로모션(여럿이면 가장 늦게 시작한 것)의 판매 중 상품을 진열 순서대로 3개
     * - 인기: 인테리어 탭의 판매 중 상품을 판매량순으로 4개
     *
     * 가격은 상품이 든 프로모션 중 사용자가 대상인 것의 할인율 가장 큰 것으로 계산한다.
     *
     * @param shopLayoutVariant 앱이 Remote Config에서 받은 배치 그룹. B가 아니면 A로 본다
     * @throws BusinessException [UserErrorCode.USER_NOT_FOUND] 사용자가 없는 경우
     */
    @Transactional(readOnly = true)
    fun getRecommendPage(userId: Long, shopLayoutVariant: String?): ShopPageResponse {
        val now = clock.instant()
        val promotions = getEligiblePromotions(userId, now)
        val promotionProducts = promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(
            promotions.map { it.id },
        )
        val discountRates = calculateMaxDiscountRates(promotions, promotionProducts)
        val productsById = productRepository.findOnSaleProducts(now).associateBy { it.id }

        val dailyPromotion = promotions.filter { it.category == PromotionCategory.DAILY }.maxByOrNull { it.startsAt }
        val dailyProducts = dailyPromotion
            ?.let { getPromotionProductsInOrder(it.id, promotionProducts, productsById) }
            .orEmpty()
            .take(DAILY_PRODUCT_COUNT)
        val popularProducts = productsById.values
            .filter { ShopTab.INTERIOR.contains(it.category) }
            .sortedWith(compareByDescending<Product> { it.salesCount }.thenByDescending { it.id })
            .take(POPULAR_PRODUCT_COUNT)
        val shopItems =
            createShopItems(userId, (dailyProducts + popularProducts).distinctBy { it.id }, discountRates, now)

        val discountSection = createProductSection(
            header = SectionHeaderContentResponse(
                title = listOf(RichTextResponse(text = "오늘의 할인", typeScale = "titleS", color = "contentPrimary")),
                trailing = dailyPromotion?.endsAt?.let { createRemainingTimeTrailing(it, now) },
            ),
            items = dailyProducts.map { shopItems.getValue(it.id) },
            createComponent = { ItemListComponentResponse(ShopItemsContentResponse(it)) },
        )
        val popularSection = createProductSection(
            header = SectionHeaderContentResponse(
                title = listOf(RichTextResponse(text = "지금 인기 있는 가구", typeScale = "titleS", color = "contentPrimary")),
                trailing = SectionHeaderTrailingResponse(
                    content = listOf(
                        RichTextResponse(text = "전체보기", typeScale = "labelS", color = "contentSecondary"),
                        RichTextResponse(icon = "chevronRight", color = "contentSecondary"),
                    ),
                    action = createNavigateAction("/shop/interior?sort=POPULAR"),
                ),
            ),
            items = popularProducts.map { shopItems.getValue(it.id) },
            createComponent = { RankingComponentResponse(ShopItemsContentResponse(it)) },
        )
        val heroBanner = createHeroBanner(
            shopBanners = shopBannerRepository.findActiveShopBanners(now),
            promotions = promotions,
            promotionProducts = promotionProducts,
            productsById = productsById,
            discountRates = discountRates,
        )

        val isLayoutB = shopLayoutVariant?.trim() == LAYOUT_B
        val sections = if (isLayoutB) popularSection + discountSection else discountSection + popularSection
        return ShopPageResponse(pageKey = "RECOMMEND", sduiComponents = heroBanner + sections)
    }

    /**
     * 탭의 판매 중인 상품을 [sort] 순서로 [size]개씩 조회한다.
     *
     * @param category 인테리어 탭의 분류 칩. [ShopCategory.LIMITED]는 분류와 관계없이 기간 한정만 거른다
     * @param cursor 직전 묶음의 마지막 productId. null이면 처음부터 조회한다
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 방 배경 탭에 분류 칩을 보냈거나, 커서 상품이 목록에 없는 경우
     */
    fun getProducts(
        userId: Long,
        tab: ShopTab,
        category: ShopCategory?,
        sort: ProductSort,
        cursor: Long?,
        size: Int,
    ): ShopItemsResponse {
        if (tab == ShopTab.BACKGROUND && category != null) throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE)

        val sorted = ShopStubData.products
            .filter { it.isOnSale && tab.contains(it.category) }
            .filter { product ->
                when (category) {
                    null -> true
                    ShopCategory.LIMITED -> product.isLimited
                    else -> product.category == category.roomItemCategory
                }
            }
            .sortedWith(createComparator(sort))

        val page = createPage(sorted, cursor, size) { it.productId }
        return ShopItemsResponse(
            items = page.items.map { it.toShopItemResponse() },
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )
    }

    /**
     * 배너를 누르면 여는 프로모션의 판매 중인 상품을 진열 순서대로 [size]개씩 조회한다.
     * 맨 위 배너는 추천 탭과 같은 배너를 직각 모서리로, 누를 곳 없이 준다.
     * 가격은 추천 탭과 같이 상품이 든 프로모션 중 사용자가 대상인 것의 할인율 가장 큰 것으로 계산한다.
     *
     * @param cursor 직전 묶음의 마지막 productId. null이면 처음부터 조회한다
     * @throws BusinessException [ShopErrorCode.PROMOTION_NOT_FOUND] 진행 중인 프로모션이 아니거나, 대상 사용자가 아니거나,
     * 노출 중인 배너가 없는 경우
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 커서 상품이 목록에 없는 경우
     */
    @Transactional(readOnly = true)
    fun getPromotionProducts(userId: Long, promotionId: Long, cursor: Long?, size: Int): PromotionProductsResponse {
        val now = clock.instant()
        val promotions = getEligiblePromotions(userId, now)
        val promotion = promotions.find { it.id == promotionId }
            ?: throw BusinessException(ShopErrorCode.PROMOTION_NOT_FOUND)
        // 이 화면은 배너를 눌러야 들어오므로, 노출 중인 배너가 없으면 진행 중이 아닌 프로모션과 같이 본다
        val shopBanner = shopBannerRepository.findActiveShopBanners(now).find { it.promotionId == promotionId }
            ?: throw BusinessException(ShopErrorCode.PROMOTION_NOT_FOUND)

        val promotionProducts = promotionProductRepository.findByPromotionIdInOrderByDisplayOrderAsc(
            promotions.map { it.id },
        )
        val discountRates = calculateMaxDiscountRates(promotions, promotionProducts)
        val productsById = productRepository.findOnSaleProducts(now).associateBy { it.id }
        val products = getPromotionProductsInOrder(promotion.id, promotionProducts, productsById)

        val page = createPage(products, cursor, size) { it.id }
        val shopItems = createShopItems(userId, page.items, discountRates, now)
        return PromotionProductsResponse(
            banner = shopBanner.toShopBannerResponse(
                imageUrl = createImageUrl(shopBanner.imageKey),
                cornerStyle = BannerCornerStyle.SQUARE,
                price = createPromotionPriceText(promotion, products, discountRates),
                action = null,
            ),
            items = page.items.map { shopItems.getValue(it.id) },
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )
    }

    /**
     * 상단 바의 코인 잔액과 장바구니에 담긴 상품 종류 수.
     */
    fun getMe(userId: Long): ShopMeResponse =
        ShopMeResponse(balance = ShopStubData.BALANCE, cartItemCount = ShopStubData.cartItems.size)

    // 진행 중인 프로모션 중 사용자가 대상인 것
    private fun getEligiblePromotions(userId: Long, now: Instant): List<Promotion> {
        val user = userRepository.findByIdOrNull(userId) ?: throw BusinessException(UserErrorCode.USER_NOT_FOUND)

        return promotionRepository.findActivePromotions(now)
            .filter { it.category.isEligible(user.createdAt, now, shopProperties.newUserTimeToLive) }
    }

    // 프로모션의 판매 중 상품을 진열 순서대로. promotionProducts가 이미 진열 순서라 거르기만 한다.
    private fun getPromotionProductsInOrder(
        promotionId: Long,
        promotionProducts: List<PromotionProduct>,
        productsById: Map<Long, Product>,
    ): List<Product> = promotionProducts
        .filter { it.promotionId == promotionId }
        .mapNotNull { productsById[it.productId] }

    // 상품 카드를 productId로 찾게 만든다. 보유 개수는 단품만 세고(세트는 0), 한 번의 쿼리로 센다.
    private fun createShopItems(
        userId: Long,
        products: List<Product>,
        discountRates: Map<Long, Int>,
        now: Instant,
    ): Map<Long, ShopItemResponse> {
        if (products.isEmpty()) return emptyMap()

        val itemCounts = productItemRepository.findByProductIdIn(products.map { it.id })
            .groupingBy { it.productId }
            .eachCount()
        val singleProducts = products.filter { itemCounts[it.id] == 1 }
        val ownedCounts = if (singleProducts.isEmpty()) {
            emptyMap()
        } else {
            ownedRoomItemRepository.findOwnedRoomItemCounts(userId, singleProducts.map { it.representativeItemId })
                .associate { it.itemId to it.ownedCount.toInt() }
        }
        val newProductStartsAt = now.minus(shopProperties.newProductTimeToLive)

        return products.associate { product ->
            val ownedCount = if (itemCounts[product.id] == 1) ownedCounts[product.representativeItemId] ?: 0 else 0
            product.id to product.toShopItemResponse(
                purchasePrice = calculatePurchasePrice(product.price, discountRates[product.id]),
                isNew = product.saleStartsAt.isAfter(newProductStartsAt),
                ownedCount = ownedCount,
            )
        }
    }

    // 알리는 프로모션이 진행 중이고 사용자가 대상인 배너만 남긴다. 배너가 없으면 칸을 뺀다.
    private fun createHeroBanner(
        shopBanners: List<ShopBanner>,
        promotions: List<Promotion>,
        promotionProducts: List<PromotionProduct>,
        productsById: Map<Long, Product>,
        discountRates: Map<Long, Int>,
    ): List<SduiComponentResponse> {
        val promotionsById = promotions.associateBy { it.id }
        val banners = shopBanners
            .filter { it.promotionId in promotionsById }
            .map { shopBanner ->
                val promotion = promotionsById.getValue(shopBanner.promotionId)
                val products = getPromotionProductsInOrder(promotion.id, promotionProducts, productsById)
                shopBanner.toShopBannerResponse(
                    imageUrl = createImageUrl(shopBanner.imageKey),
                    cornerStyle = BannerCornerStyle.ROUNDED,
                    price = createPromotionPriceText(promotion, products, discountRates),
                    action = createNavigateAction("/shop/promotions/${promotion.id}"),
                )
            }
        if (banners.isEmpty()) return emptyList()

        return listOf(HeroBannerComponentResponse(HeroBannerContentResponse(banners)))
    }

    // 할인 프로모션이면 상품 전체를 묶음으로 본 가격(정가 합 → 결제가 합)을 보여준다.
    // 할인율이 없는 프로모션(기간 한정 묶음 등)은 다른 프로모션 할인이 걸려 있어도 가격을 보여주지 않는다.
    private fun createPromotionPriceText(
        promotion: Promotion,
        products: List<Product>,
        discountRates: Map<Long, Int>,
    ): List<RichTextResponse>? {
        if (promotion.discountRate == null) return null

        val listPrice = products.sumOf { it.price }
        val purchasePrice = products.sumOf { calculatePurchasePrice(it.price, discountRates[it.id]) }
        if (purchasePrice >= listPrice) return null

        return createPriceText(listPrice, purchasePrice)
    }

    // CDN 주소 + S3 키
    private fun createImageUrl(imageKey: String): String = "${shopProperties.cdnBaseUrl}/$imageKey"

    // "N시간 후 바뀌어요". 남은 시간은 시간 단위로 올린다(1시간 10분 → 2시간).
    private fun createRemainingTimeTrailing(endsAt: Instant, now: Instant): SectionHeaderTrailingResponse {
        val remainingHours = (Duration.between(now, endsAt).seconds + 3599) / 3600

        return SectionHeaderTrailingResponse(
            content = listOf(
                RichTextResponse(icon = "clock", color = "contentTertiary"),
                RichTextResponse(text = "${remainingHours}시간 후 바뀌어요", typeScale = "labelS", color = "contentTertiary"),
            ),
            action = null,
        )
    }

    // 상품이 없으면 그 위 제목 줄까지 같이 뺀다. 앱은 빈 칸을 받지 않는다.
    private fun createProductSection(
        header: SectionHeaderContentResponse,
        items: List<ShopItemResponse>,
        createComponent: (List<ShopItemResponse>) -> SduiComponentResponse,
    ): List<SduiComponentResponse> {
        if (items.isEmpty()) return emptyList()
        return listOf(SectionHeaderComponentResponse(header), createComponent(items))
    }

    // 값이 같으면 productId 내림차순
    private fun createComparator(sort: ProductSort): Comparator<StubProduct> = when (sort) {
        ProductSort.NEW -> compareByDescending { it.productId }

        ProductSort.POPULAR -> compareByDescending<StubProduct> { it.salesCount }.thenByDescending { it.productId }

        ProductSort.PRICE_ASC -> compareBy<StubProduct> { it.purchasePrice }.thenByDescending { it.productId }

        ProductSort.PRICE_DESC -> compareByDescending<StubProduct> {
            it.purchasePrice
        }.thenByDescending { it.productId }
    }

    private data class ProductPageResult<T>(val items: List<T>, val nextCursor: Long?, val hasNext: Boolean)

    // 정렬된 목록에서 커서 상품 다음부터 size개를 자른다. 커서 상품이 목록에 없으면 처음부터 주지 않고 거부한다.
    private fun <T> createPage(
        products: List<T>,
        cursor: Long?,
        size: Int,
        getProductId: (T) -> Long,
    ): ProductPageResult<T> {
        val startIndex = cursor?.let { cursorId ->
            val index = products.indexOfFirst { getProductId(it) == cursorId }
            if (index < 0) throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE)
            index + 1
        } ?: 0
        val page = products.drop(startIndex).take(size)
        val hasNext = products.size > startIndex + size

        return ProductPageResult(
            items = page,
            nextCursor = if (hasNext) getProductId(page.last()) else null,
            hasNext = hasNext,
        )
    }
}

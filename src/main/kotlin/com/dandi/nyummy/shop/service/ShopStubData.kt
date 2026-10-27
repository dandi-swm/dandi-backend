package com.dandi.nyummy.shop.service

import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.dto.ActionResponse
import com.dandi.nyummy.shop.dto.HeroBannerComponentResponse
import com.dandi.nyummy.shop.dto.HeroBannerContentResponse
import com.dandi.nyummy.shop.dto.RichTextResponse
import com.dandi.nyummy.shop.dto.ShopBannerResponse
import com.dandi.nyummy.shop.dto.ShopItemResponse
import com.dandi.nyummy.shop.dto.TagResponse
import com.dandi.nyummy.shop.enum.BannerCornerStyle
import com.dandi.nyummy.shop.enum.TagTone
import java.util.Locale

/**
 * 상점 해피케이스의 고정 데이터. 상품·장바구니 테이블을 붙이기 전까지 [ShopService]와 [CartService]가 이 값으로 응답한다.
 *
 * 테이블을 붙이면 이 파일을 지우고 서비스만 고친다. 컨트롤러와 응답 DTO는 그대로 둔다.
 */
internal object ShopStubData {

    const val BALANCE = 2480
    const val MAX_QUANTITY_PER_PRODUCT = 10
    const val MAX_BACKGROUND_QUANTITY = 1
    const val MAX_CART_QUANTITY = 30

    private const val LINK = "https://link.nyummy.co.kr"

    data class StubProduct(
        val productId: Long,
        val itemId: Long,
        val name: String,
        val category: RoomItemCategory,
        val listPrice: Int,
        val purchasePrice: Int = listPrice,
        val isLimited: Boolean = false,
        val isNew: Boolean = false,
        val isOnSale: Boolean = true,
        val ownedCount: Int = 0,
        val salesCount: Int = 0,
    ) {
        val isOwnedBackground: Boolean get() = category == RoomItemCategory.BACKGROUND && ownedCount > 0
        val canPurchase: Boolean get() = isOnSale && !isOwnedBackground
        val maxQuantityPerProduct: Int
            get() = if (category == RoomItemCategory.BACKGROUND) MAX_BACKGROUND_QUANTITY else MAX_QUANTITY_PER_PRODUCT
    }

    data class StubCartItem(val productId: Long, val quantity: Int, val isSelected: Boolean)

    val products = listOf(
        StubProduct(101, 11, "돔 고양이 침대", RoomItemCategory.BED, 650, salesCount = 80),
        StubProduct(114, 14, "구름 고양이 침대", RoomItemCategory.BED, 700, salesCount = 120),
        StubProduct(117, 17, "발바닥 방석", RoomItemCategory.BED, 150, ownedCount = 1, salesCount = 60),
        StubProduct(113, 13, "세이지 캣휠", RoomItemCategory.CAT_WHEEL, 1100, salesCount = 150),
        StubProduct(
            115,
            15,
            "크림 캣휠",
            RoomItemCategory.CAT_WHEEL,
            1100,
            purchasePrice = 880,
            isLimited = true,
            salesCount = 40,
        ),
        StubProduct(127, 27, "둥근 러그", RoomItemCategory.RUG, 400, salesCount = 55),
        StubProduct(129, 29, "단풍 러그", RoomItemCategory.RUG, 400, isLimited = true, isOnSale = false),
        StubProduct(116, 16, "생선 액자", RoomItemCategory.ETC, 200, isNew = true, salesCount = 10),
        StubProduct(121, 21, "플로어 조명", RoomItemCategory.ETC, 400, purchasePrice = 320, salesCount = 70),
        StubProduct(122, 22, "화분 선반", RoomItemCategory.ETC, 450, salesCount = 30),
        StubProduct(123, 23, "원목 책장", RoomItemCategory.ETC, 850, salesCount = 110),
        StubProduct(124, 24, "몬스테라 화분", RoomItemCategory.ETC, 300, salesCount = 90),
        StubProduct(131, 1, "줄무늬 원목 방", RoomItemCategory.BACKGROUND, 800, ownedCount = 1, salesCount = 50),
        StubProduct(132, 32, "밤하늘 방", RoomItemCategory.BACKGROUND, 1000, salesCount = 45),
        StubProduct(133, 33, "민트 체크 방", RoomItemCategory.BACKGROUND, 1000, salesCount = 35),
        StubProduct(134, 34, "별빛 다락방", RoomItemCategory.BACKGROUND, 1200, isLimited = true, salesCount = 25),
        StubProduct(135, 35, "벚꽃 방", RoomItemCategory.BACKGROUND, 1200, isNew = true, salesCount = 5),
    )

    val productsById = products.associateBy { it.productId }

    // "결제 정보" 화면과 같은 값이 나오는 장바구니(상품 4개, 결제할 코인 1,620). 요청마다 여기서 시작하고 저장하지 않는다.
    val cartItems = listOf(
        StubCartItem(productId = 117, quantity = 2, isSelected = true),
        StubCartItem(productId = 121, quantity = 1, isSelected = true),
        StubCartItem(productId = 133, quantity = 1, isSelected = true),
        StubCartItem(productId = 129, quantity = 1, isSelected = false),
    )

    /**
     * 진행 중인 프로모션. 배너 한 장이 프로모션 하나를 알리고, 누르면 그 상품 목록을 연다.
     *
     * @property products 목록에 보일 상품. 이 순서대로 진열한다
     * @property titleColor 배너 그림에 맞춘 제목 색 토큰. 어두운 그림 위에서는 contentOnInverse
     */
    data class StubPromotion(
        val promotionId: Long,
        val bannerId: Long,
        val title: String,
        val description: String,
        val imageUrl: String,
        val titleColor: String,
        val descriptionColor: String,
        val products: List<StubPromotionProduct>,
    )

    /**
     * 프로모션에 진열한 상품 하나.
     *
     * @property purchasePrice 이 프로모션에서 파는 1개 가격. null이면 상품의 평소 결제가 그대로
     */
    data class StubPromotionProduct(val productId: Long, val purchasePrice: Int? = null)

    val promotions = listOf(
        StubPromotion(
            promotionId = 3,
            bannerId = 21,
            title = "신입 집사 환영",
            description = "가입하고 7일 동안 가구 30% 할인",
            imageUrl = "https://cdn.nyummy.co.kr/shop/banners/new-butler.png",
            titleColor = "contentPrimary",
            descriptionColor = "contentSecondary",
            products = listOf(
                StubPromotionProduct(productId = 101, purchasePrice = 455),
                StubPromotionProduct(productId = 117, purchasePrice = 105),
                StubPromotionProduct(productId = 127, purchasePrice = 280),
            ),
        ),
        StubPromotion(
            promotionId = 4,
            bannerId = 22,
            title = "첫 구매 혜택",
            description = "처음 사는 가구는 20% 할인",
            imageUrl = "https://cdn.nyummy.co.kr/shop/banners/first-purchase.png",
            titleColor = "contentOnInverse",
            descriptionColor = "contentOnInverse",
            products = listOf(StubPromotionProduct(productId = 121), StubPromotionProduct(productId = 115)),
        ),
        StubPromotion(
            promotionId = 5,
            bannerId = 23,
            title = "다시 만나 반가워요",
            description = "돌아온 집사를 위한 가구 모음",
            imageUrl = "https://cdn.nyummy.co.kr/shop/banners/welcome-back.png",
            titleColor = "contentOnInverse",
            descriptionColor = "contentOnInverse",
            products = listOf(
                StubPromotionProduct(productId = 114),
                StubPromotionProduct(productId = 127),
                StubPromotionProduct(productId = 123),
            ),
        ),
    )

    val promotionsById = promotions.associateBy { it.promotionId }

    // 프로모션 화면에 보일 판매 중인 상품. 프로모션 가격이 있으면 그 가격으로 바꿔 둔다.
    fun StubPromotion.toPromotionProducts(): List<StubProduct> = products
        .map { promotionProduct ->
            val product = productsById.getValue(promotionProduct.productId)
            promotionProduct.purchasePrice?.let { product.copy(purchasePrice = it) } ?: product
        }
        .filter { it.isOnSale }

    // 추천 탭 배너는 둥근 모서리이고, 누르면 그 프로모션 화면을 연다
    fun createHeroBanner(): HeroBannerComponentResponse = HeroBannerComponentResponse(
        HeroBannerContentResponse(
            banners = promotions.map { promotion ->
                createBanner(
                    promotion = promotion,
                    cornerStyle = BannerCornerStyle.ROUNDED,
                    action = createNavigateAction("/shop/promotions/${promotion.promotionId}"),
                )
            },
        ),
    )

    // 프로모션 화면 맨 위 배너는 직각 모서리이고, 이미 그 화면이라 누를 곳이 없다
    fun createPromotionBanner(promotion: StubPromotion): ShopBannerResponse =
        createBanner(promotion = promotion, cornerStyle = BannerCornerStyle.SQUARE, action = null)

    private fun createBanner(
        promotion: StubPromotion,
        cornerStyle: BannerCornerStyle,
        action: ActionResponse?,
    ): ShopBannerResponse = ShopBannerResponse(
        bannerId = promotion.bannerId,
        imageUrl = promotion.imageUrl,
        cornerStyle = cornerStyle,
        title = listOf(RichTextResponse(text = promotion.title, typeScale = "displayM", color = promotion.titleColor)),
        description = listOf(
            RichTextResponse(text = promotion.description, typeScale = "labelS", color = promotion.descriptionColor),
        ),
        price = createPromotionPriceText(promotion),
        action = action,
    )

    // 프로모션 상품 전체를 묶음으로 본 가격. 정가 합보다 결제가 합이 낮을 때만 할인율과 함께 보여주고, 할인이 없으면 null이다.
    private fun createPromotionPriceText(promotion: StubPromotion): List<RichTextResponse>? {
        val products = promotion.toPromotionProducts()
        val listPrice = products.sumOf { it.listPrice }
        val purchasePrice = products.sumOf { it.purchasePrice }
        if (purchasePrice >= listPrice) return null
        return createPriceText(listPrice, purchasePrice)
    }

    fun createNavigateAction(path: String): ActionResponse =
        ActionResponse(actionType = "navigate", navigateLink = LINK + path, message = null)

    fun StubProduct.toShopItemResponse(): ShopItemResponse = ShopItemResponse(
        productId = productId,
        itemId = itemId,
        name = name,
        category = category,
        categoryName = category.description,
        price = createPriceText(listPrice, purchasePrice),
        tag = createTag(this),
        isLimited = isLimited,
        ownedCount = ownedCount,
    )

    // 한정 > 할인 > 신규 순서로 하나만 붙인다
    private fun createTag(product: StubProduct): TagResponse? = when {
        product.isLimited -> TagResponse("한정", TagTone.PROMO)
        product.purchasePrice < product.listPrice -> TagResponse("할인", TagTone.PROMO)
        product.isNew -> TagResponse("신규", TagTone.PROMO)
        else -> null
    }

    // 할인 중이면 "20% (코인) 880 1,100(취소선)", 아니면 "(코인) 650". 할인율은 내림이다(1,600 → 1,400이면 12%).
    private fun createPriceText(listPrice: Int, purchasePrice: Int): List<RichTextResponse> {
        val coin = RichTextResponse(icon = "coin")
        if (purchasePrice >= listPrice) {
            return listOf(
                coin,
                RichTextResponse(
                    text = convertCoinToText(purchasePrice),
                    typeScale = "numberS",
                    color = "contentPrimary",
                ),
            )
        }

        val discountRate = (listPrice - purchasePrice) * 100 / listPrice
        return listOf(
            RichTextResponse(text = "$discountRate%", typeScale = "numberS", color = "contentPromo"),
            coin,
            RichTextResponse(
                text = "${convertCoinToText(purchasePrice)} ",
                typeScale = "numberS",
                color = "contentPrimary",
            ),
            RichTextResponse(
                text = convertCoinToText(listPrice),
                typeScale = "labelS",
                color = "contentTertiary",
                strike = true,
            ),
        )
    }

    private fun convertCoinToText(amount: Int): String = String.format(Locale.KOREA, "%,d", amount)
}

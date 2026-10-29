package com.dandi.nyummy.shop.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
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
import com.dandi.nyummy.shop.enum.ProductSort
import com.dandi.nyummy.shop.enum.ShopCategory
import com.dandi.nyummy.shop.enum.ShopTab
import com.dandi.nyummy.shop.mapper.createNavigateAction
import com.dandi.nyummy.shop.service.ShopStubData.StubProduct
import com.dandi.nyummy.shop.service.ShopStubData.toPromotionProducts
import com.dandi.nyummy.shop.service.ShopStubData.toShopItemResponse
import org.springframework.stereotype.Service

/**
 * 상점 추천 탭·상품 목록·프로모션 상품 목록·상단 바. 지금은 [ShopStubData]의 고정 데이터로 응답한다.
 */
@Service
class ShopService {

    companion object {
        private const val LAYOUT_B = "B"
    }

    /**
     * 추천 탭 SDUI 칸 목록. A는 배너 → 오늘의 할인 → 인기, B는 배너 → 인기 → 오늘의 할인 순이다.
     * 상품이 없는 섹션은 위 제목 줄까지 같이 뺀다.
     *
     * @param shopLayoutVariant 앱이 Remote Config에서 받은 배치 그룹. B가 아니면 A로 본다
     */
    fun getRecommendPage(userId: Long, shopLayoutVariant: String?): ShopPageResponse {
        val interiorProducts = ShopStubData.products.filter { it.isOnSale && ShopTab.INTERIOR.contains(it.category) }
        val discountSection = createProductSection(
            header = SectionHeaderContentResponse(
                title = listOf(RichTextResponse(text = "오늘의 할인", typeScale = "titleS", color = "contentPrimary")),
                trailing = SectionHeaderTrailingResponse(
                    content = listOf(
                        RichTextResponse(icon = "clock", color = "contentTertiary"),
                        RichTextResponse(text = "9시간 후 바뀌어요", typeScale = "labelS", color = "contentTertiary"),
                    ),
                    action = null,
                ),
            ),
            products = interiorProducts.filter { it.purchasePrice < it.listPrice }.take(5),
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
            products = interiorProducts.sortedByDescending { it.salesCount }.take(4),
            createComponent = { RankingComponentResponse(ShopItemsContentResponse(it)) },
        )

        val isLayoutB = shopLayoutVariant?.trim() == LAYOUT_B
        val sections = if (isLayoutB) popularSection + discountSection else discountSection + popularSection
        return ShopPageResponse(
            pageKey = "RECOMMEND",
            sduiComponents =
            listOf(ShopStubData.createHeroBanner()) + sections,
        )
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

        val page = createPage(sorted, cursor, size)
        return ShopItemsResponse(items = page.items, nextCursor = page.nextCursor, hasNext = page.hasNext)
    }

    /**
     * 배너를 누르면 여는 프로모션의 판매 중인 상품을 진열 순서대로 [size]개씩 조회한다.
     *
     * @param cursor 직전 묶음의 마지막 productId. null이면 처음부터 조회한다
     * @throws BusinessException [ShopErrorCode.PROMOTION_NOT_FOUND] 진행 중인 프로모션이 아니거나 대상 사용자가 아닌 경우
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 커서 상품이 목록에 없는 경우
     */
    fun getPromotionProducts(userId: Long, promotionId: Long, cursor: Long?, size: Int): PromotionProductsResponse {
        val promotion = ShopStubData.promotionsById[promotionId]
            ?: throw BusinessException(ShopErrorCode.PROMOTION_NOT_FOUND)
        val products = promotion.toPromotionProducts()

        val page = createPage(products, cursor, size)
        return PromotionProductsResponse(
            banner = ShopStubData.createPromotionBanner(promotion),
            items = page.items,
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )
    }

    /**
     * 상단 바의 코인 잔액과 장바구니에 담긴 상품 종류 수.
     */
    fun getMe(userId: Long): ShopMeResponse =
        ShopMeResponse(balance = ShopStubData.BALANCE, cartItemCount = ShopStubData.cartItems.size)

    // 상품이 없으면 그 위 제목 줄까지 같이 뺀다. 앱은 빈 칸을 받지 않는다.
    private fun createProductSection(
        header: SectionHeaderContentResponse,
        products: List<StubProduct>,
        createComponent: (List<ShopItemResponse>) -> SduiComponentResponse,
    ): List<SduiComponentResponse> {
        if (products.isEmpty()) return emptyList()
        return listOf(SectionHeaderComponentResponse(header), createComponent(products.map { it.toShopItemResponse() }))
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

    private data class ProductPageResult(val items: List<ShopItemResponse>, val nextCursor: Long?, val hasNext: Boolean)

    // 정렬된 목록에서 커서 상품 다음부터 size개를 자른다. 커서 상품이 목록에 없으면 처음부터 주지 않고 거부한다.
    private fun createPage(products: List<StubProduct>, cursor: Long?, size: Int): ProductPageResult {
        val startIndex = cursor?.let { cursorId ->
            val index = products.indexOfFirst { it.productId == cursorId }
            if (index < 0) throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE)
            index + 1
        } ?: 0
        val page = products.drop(startIndex).take(size)
        val hasNext = products.size > startIndex + size

        return ProductPageResult(
            items = page.map { it.toShopItemResponse() },
            nextCursor = if (hasNext) page.last().productId else null,
            hasNext = hasNext,
        )
    }
}

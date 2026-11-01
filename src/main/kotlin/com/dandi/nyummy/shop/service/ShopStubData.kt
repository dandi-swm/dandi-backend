package com.dandi.nyummy.shop.service

import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.dto.ShopItemResponse
import com.dandi.nyummy.shop.mapper.createPriceText
import com.dandi.nyummy.shop.mapper.createTag

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

    fun StubProduct.toShopItemResponse(): ShopItemResponse = ShopItemResponse(
        productId = productId,
        itemId = itemId,
        name = name,
        category = category,
        categoryName = category.description,
        price = createPriceText(listPrice, purchasePrice),
        tag = createTag(isLimited = isLimited, isDiscounted = purchasePrice < listPrice, isNew = isNew),
        isLimited = isLimited,
        ownedCount = ownedCount,
    )
}

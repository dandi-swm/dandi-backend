package com.dandi.nyummy.shop.mapper

import com.dandi.nyummy.shop.dto.ActionResponse
import com.dandi.nyummy.shop.dto.RichTextResponse
import com.dandi.nyummy.shop.dto.ShopBannerResponse
import com.dandi.nyummy.shop.dto.ShopItemResponse
import com.dandi.nyummy.shop.dto.TagResponse
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ShopBanner
import com.dandi.nyummy.shop.enum.BannerCornerStyle
import com.dandi.nyummy.shop.enum.TagTone
import java.util.Locale

private const val LINK = "https://link.nyummy.co.kr"

/**
 * @param purchasePrice 이 사용자의 1개 결제가
 * @param isNew 판매를 시작한 지 얼마 안 된 상품인지
 * @param ownedCount 단품은 대표 아이템 보유 개수, 세트는 0
 */
fun Product.toShopItemResponse(purchasePrice: Int, isNew: Boolean, ownedCount: Int): ShopItemResponse =
    ShopItemResponse(
        productId = this.id,
        itemId = this.representativeItemId,
        name = this.name,
        category = this.category,
        categoryName = this.category.description,
        price = createPriceText(this.price, purchasePrice),
        tag = createTag(isLimited = this.isLimited, isDiscounted = purchasePrice < this.price, isNew = isNew),
        isLimited = this.isLimited,
        ownedCount = ownedCount,
    )

/**
 * @param price 프로모션 상품 전체를 묶음으로 본 가격 글자. 할인이 없으면 null
 * @param action 누르면 할 일. 프로모션 화면 맨 위 배너는 null
 */
fun ShopBanner.toShopBannerResponse(
    imageUrl: String,
    cornerStyle: BannerCornerStyle,
    price: List<RichTextResponse>?,
    action: ActionResponse?,
): ShopBannerResponse = ShopBannerResponse(
    bannerId = this.id,
    imageUrl = imageUrl,
    cornerStyle = cornerStyle,
    title = listOf(RichTextResponse(text = this.title, typeScale = "displayM", color = this.titleColor)),
    description = this.description?.let {
        listOf(RichTextResponse(text = it, typeScale = "labelS", color = this.descriptionColor))
    },
    price = price,
    action = action,
)

fun createNavigateAction(path: String): ActionResponse =
    ActionResponse(actionType = "navigate", navigateLink = LINK + path, message = null)

// 한정 > 할인 > 신규 순서로 하나만 붙인다
fun createTag(isLimited: Boolean, isDiscounted: Boolean, isNew: Boolean): TagResponse? = when {
    isLimited -> TagResponse("한정", TagTone.PROMO)
    isDiscounted -> TagResponse("할인", TagTone.PROMO)
    isNew -> TagResponse("신규", TagTone.PROMO)
    else -> null
}

// 할인 중이면 "20% (코인) 880 1,100(취소선)", 아니면 "(코인) 650". 할인율은 내림이다(1,600 → 1,400이면 12%).
fun createPriceText(listPrice: Int, purchasePrice: Int): List<RichTextResponse> {
    val coin = RichTextResponse(icon = "coin")
    if (purchasePrice >= listPrice) {
        return listOf(
            coin,
            RichTextResponse(text = convertCoinToText(purchasePrice), typeScale = "numberS", color = "contentPrimary"),
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

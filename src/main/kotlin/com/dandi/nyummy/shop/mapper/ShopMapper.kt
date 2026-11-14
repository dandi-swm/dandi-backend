package com.dandi.nyummy.shop.mapper

import com.dandi.nyummy.shop.dto.ActionResponse
import com.dandi.nyummy.shop.dto.RichTextResponse
import com.dandi.nyummy.shop.dto.ShopBannerResponse
import com.dandi.nyummy.shop.dto.ShopItemResponse
import com.dandi.nyummy.shop.dto.TagResponse
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.ShopBanner
import com.dandi.nyummy.shop.enum.BannerCornerStyle
import java.util.Locale

private const val LINK = "https://link.nyummy.co.kr"

/**
 * @param name 요청 언어로 고른 상품 이름
 * @param purchasePrice 이 사용자의 1개 결제가
 * @param ownedCount 단품은 대표 아이템 보유 개수 + 우편함에서 아직 받지 않은 구매분, 세트는 0
 * @param categoryName 요청 언어로 꺼낸 분류 이름
 * @param tag 요청 언어로 꺼낸 태그. 붙일 태그가 없으면 null ([pickTagMessageKey])
 */
fun Product.toShopItemResponse(
    name: String,
    purchasePrice: Int,
    ownedCount: Int,
    categoryName: String,
    tag: TagResponse?,
): ShopItemResponse = ShopItemResponse(
    productId = this.id,
    itemId = this.representativeItemId,
    name = name,
    category = this.category,
    categoryName = categoryName,
    price = createPriceText(this.price, purchasePrice),
    tag = tag,
    isLimited = this.isLimited,
    ownedCount = ownedCount,
)

/**
 * @param title 요청 언어로 고른 제목
 * @param description 요청 언어로 고른 설명. 설명이 없는 배너는 null
 * @param price 프로모션 상품 전체를 묶음으로 본 가격 글자. 할인이 없으면 null
 * @param action 누르면 할 일. 프로모션 화면 맨 위 배너는 null
 */
fun ShopBanner.toShopBannerResponse(
    title: String,
    description: String?,
    imageUrl: String,
    cornerStyle: BannerCornerStyle,
    price: List<RichTextResponse>?,
    action: ActionResponse?,
): ShopBannerResponse = ShopBannerResponse(
    bannerId = this.id,
    imageUrl = imageUrl,
    cornerStyle = cornerStyle,
    title = listOf(RichTextResponse(text = title, typeScale = "displayM", color = this.titleColor)),
    description = description?.let {
        listOf(RichTextResponse(text = it, typeScale = "labelS", color = this.descriptionColor))
    },
    price = price,
    action = action,
)

fun createNavigateAction(path: String): ActionResponse =
    ActionResponse(actionType = "navigate", navigateLink = LINK + path, message = null)

/**
 * 한정 > 할인 > 신규 순서로 하나만 붙인다. 글자는 호출하는 쪽이 이 키로 요청 언어 문구를 꺼낸다.
 *
 * @return 서버 고정 문구 키. 붙일 태그가 없으면 null
 */
fun pickTagMessageKey(isLimited: Boolean, isDiscounted: Boolean, isNew: Boolean): String? = when {
    isLimited -> "shop.tag.limited"
    isDiscounted -> "shop.tag.discounted"
    isNew -> "shop.tag.new"
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
        RichTextResponse(text = "$discountRate%", typeScale = "numberS", color = "contentDanger"),
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

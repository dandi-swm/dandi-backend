@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.shop.dto

import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.enum.BannerCornerStyle
import com.dandi.nyummy.shop.enum.TagTone
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 서버가 그리는 상점 화면 하나.
 *
 * @property sduiComponents 위에서 아래로 그릴 칸 목록. 앱은 모르는 viewType이나 필수 필드가 빠진 칸만 빼고 그린다
 */
data class ShopPageResponse(val pageKey: String, val sduiComponents: List<SduiComponentResponse>)

/**
 * SDUI 칸 하나. [viewType]마다 [content]의 모양이 정해져 있다.
 */
@Schema(
    oneOf = [
        HeroBannerComponentResponse::class,
        SectionHeaderComponentResponse::class,
        ItemGridComponentResponse::class,
        RankingComponentResponse::class,
        ItemListComponentResponse::class,
    ],
)
sealed interface SduiComponentResponse {
    val viewType: String
}

/** 옆으로 넘기는 큰 배너 */
data class HeroBannerComponentResponse(val content: HeroBannerContentResponse) : SduiComponentResponse {
    override val viewType = "HeroBanner"
}

/** 아래 칸의 제목 줄. 다른 칸 안에는 제목이 없다 */
data class SectionHeaderComponentResponse(val content: SectionHeaderContentResponse) : SduiComponentResponse {
    override val viewType = "SectionHeader"
}

/** 상품 3열 카드. 3의 배수를 권장한다 */
data class ItemGridComponentResponse(val content: ShopItemsContentResponse) : SduiComponentResponse {
    override val viewType = "ItemGrid"
}

/** 순위 숫자 + 상품 줄. 순서가 곧 순위이고, 3~5개를 권장한다 */
data class RankingComponentResponse(val content: ShopItemsContentResponse) : SduiComponentResponse {
    override val viewType = "Ranking"
}

/** 상품 줄 목록. 1~5개를 권장한다 */
data class ItemListComponentResponse(val content: ShopItemsContentResponse) : SduiComponentResponse {
    override val viewType = "ItemList"
}

/**
 * @property banners 넘기는 배너 목록. 비면 서버가 칸을 빼서 보낸다
 */
data class HeroBannerContentResponse(val banners: List<ShopBannerResponse>)

/**
 * 프로모션(신입 집사 환영, 첫 구매 혜택 등)을 알리는 배너 한 장.
 *
 * @property imageUrl 배너 그림 주소. 7:4이고 그림 안에 글자가 없다
 * @property cornerStyle 배너 모서리 모양. 추천 탭은 ROUNDED, 프로모션 화면 맨 위는 SQUARE
 * @property title 큰 제목. 배너 그림에 맞춰 색 토큰을 고른다(어두운 그림 위에서는 contentOnInverse)
 * @property description 제목 아래 한 줄. 없으면 null
 * @property price 할인 프로모션의 대표 상품 가격 글자(할인율, 코인, 할인가, 취소선 정가). 할인 프로모션이 아니면 null
 * @property action 누르면 그 프로모션 화면(/shop/promotions/{promotionId})을 연다. 프로모션 화면 맨 위 배너는 null
 */
data class ShopBannerResponse(
    val bannerId: Long,
    val imageUrl: String,
    val cornerStyle: BannerCornerStyle,
    val title: List<RichTextResponse>,
    val description: List<RichTextResponse>?,
    val price: List<RichTextResponse>?,
    val action: ActionResponse?,
)

/**
 * @property trailing 오른쪽 영역("전체보기 >", "9시간 후 바뀌어요"). 없으면 오른쪽을 비운다
 */
data class SectionHeaderContentResponse(val title: List<RichTextResponse>, val trailing: SectionHeaderTrailingResponse?)

/**
 * @property action 있으면 오른쪽 영역 전체를 누를 수 있다
 */
data class SectionHeaderTrailingResponse(val content: List<RichTextResponse>, val action: ActionResponse?)

data class ShopItemsContentResponse(val items: List<ShopItemResponse>)

/**
 * 상품 한 개. 추천 탭의 상품 칸, 상품 목록, 상품 상세, 장바구니가 같이 쓴다.
 *
 * @property productId 장바구니·구매에 쓰는 상품 id
 * @property itemId 그림 id. 앱이 `https://cdn.nyummy.co.kr/items/{itemId}.png`로 조립한다. 세트는 대표 아이템이다
 * @property price 가격 글자. 할인율, 코인 아이콘, 할인가, 취소선 원가까지 서버가 만든다
 * @property tag 카드당 하나. 한정 > 할인 > 신규 순서로 서버가 고른다
 * @property ownedCount 보유 개수 + 우편함에서 아직 받지 않은 구매분. 앱이 "n개 보유" 태그를 그린다
 */
data class ShopItemResponse(
    val productId: Long,
    val itemId: Long,
    val name: String,
    val category: RoomItemCategory,
    val categoryName: String,
    val price: List<RichTextResponse>,
    val tag: TagResponse?,
    val isLimited: Boolean,
    val ownedCount: Int,
)

/**
 * 꾸민 글자의 조각 하나. RichText는 이 조각의 목록이고, 앱은 순서대로 이어 붙인다.
 * 띄어쓰기와 줄바꿈(\n)은 [text]에 넣고, 아이콘 옆 간격은 앱이 넣는다.
 *
 * @property text 글자. 아이콘 조각이면 null
 * @property typeScale 글자 토큰(displayM, titleS, labelS, numberS). 모르는 값이면 앱이 bodyM으로 그린다
 * @property color 색 토큰(contentPrimary, contentSecondary, contentTertiary, contentPromo, contentOnInverse). 모르는 값이면 앱이 contentPrimary로 그린다
 * @property icon 아이콘 조각(coin, clock, chevronRight). clock, chevronRight는 [color]로 색을 정한다
 * @property strike 취소선. null이면 없음
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RichTextResponse(
    val text: String? = null,
    val typeScale: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val strike: Boolean? = null,
)

/**
 * 상품 카드의 태그. 보유 태그는 서버가 보내지 않는다.
 */
data class TagResponse(val text: String, val tone: TagTone)

/**
 * 누르면 할 일. 상품 카드는 action 없이 늘 상세 시트를 연다.
 *
 * @property actionType navigate 또는 showToast
 * @property navigateLink navigate일 때 앱 링크(https://link.nyummy.co.kr/...)
 * @property message showToast일 때 문구
 */
data class ActionResponse(val actionType: String, val navigateLink: String?, val message: String?)

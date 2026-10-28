package com.dandi.nyummy.shop.enum

/**
 * 프로모션의 대상. 사용자가 대상인지 판정하는 기준값(가입 후 일수 등)은 설정에 둔다.
 *
 * 인테리어 탭의 분류 칩 [ShopCategory], 아이템 분류 [com.dandi.nyummy.room.enum.RoomItemCategory]와 다른 enum이다.
 */
enum class PromotionCategory {
    /** 가입한 지 얼마 안 된 사용자(신입 집사 환영) */
    NEW_USER,

    /** 아직 한 번도 사지 않은 사용자(첫 구매 혜택) */
    FIRST_PURCHASE,

    /** 한동안 접속하지 않다가 돌아온 사용자(복귀 유저 혜택) */
    RETURNING_USER,

    /** 모든 사용자, 하루 단위로 상품이 바뀐다(오늘의 할인) */
    DAILY,

    /** 모든 사용자 */
    GENERAL,
}

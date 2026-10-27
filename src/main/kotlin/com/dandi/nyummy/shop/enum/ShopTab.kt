package com.dandi.nyummy.shop.enum

import com.dandi.nyummy.room.enum.RoomItemCategory

/**
 * 일반 조회로 받는 상점 탭. 추천 탭은 SDUI 페이지라 여기에 없다.
 */
enum class ShopTab {
    /** 방 배경을 뺀 마이룸 아이템 */
    INTERIOR,

    /** 방 배경 */
    BACKGROUND,
    ;

    /** [category]의 상품이 이 탭에 나오는지 */
    fun contains(category: RoomItemCategory): Boolean = when (this) {
        INTERIOR -> category != RoomItemCategory.BACKGROUND
        BACKGROUND -> category == RoomItemCategory.BACKGROUND
    }
}

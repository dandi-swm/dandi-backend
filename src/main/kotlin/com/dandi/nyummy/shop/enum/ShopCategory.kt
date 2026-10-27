package com.dandi.nyummy.shop.enum

import com.dandi.nyummy.room.enum.RoomItemCategory

/**
 * 인테리어 탭의 분류 칩. 한 번에 하나만 고르고, 고르지 않으면 탭 전체다.
 *
 * [LIMITED]는 아이템의 분류가 아니라 판매 방식이라 [RoomItemCategory]에 두지 않는다. 기간 한정인 침대는 두 칩에 모두 나온다.
 *
 * @property roomItemCategory 이 칩이 거르는 아이템 분류. [LIMITED]는 분류와 관계없이 거르므로 null
 */
enum class ShopCategory(val roomItemCategory: RoomItemCategory?) {
    LIMITED(null),
    BED(RoomItemCategory.BED),
    CAT_WHEEL(RoomItemCategory.CAT_WHEEL),
    RUG(RoomItemCategory.RUG),
    ETC(RoomItemCategory.ETC),
}

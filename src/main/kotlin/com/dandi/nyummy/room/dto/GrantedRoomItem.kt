package com.dandi.nyummy.room.dto

/**
 * 보유 아이템으로 지급할 아이템 하나.
 *
 * @property itemPurchaseLineId 구매한 구성품이면 그 id. 보상이면 null이다
 */
data class GrantedRoomItem(val itemId: Long, val itemPurchaseLineId: Long? = null)

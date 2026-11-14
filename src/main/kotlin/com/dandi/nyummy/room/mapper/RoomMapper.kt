package com.dandi.nyummy.room.mapper

import com.dandi.nyummy.room.config.DefaultRoomItemProperties
import com.dandi.nyummy.room.dto.EquippedRoomItemResponse
import com.dandi.nyummy.room.dto.EquippedRoomItemResult
import com.dandi.nyummy.room.dto.OwnedRoomItemResponse
import com.dandi.nyummy.room.dto.OwnedRoomItemResult
import com.dandi.nyummy.room.entity.OwnedRoomItem

/** @param name 요청 언어로 고른 아이템 이름 */
fun OwnedRoomItemResult.toOwnedRoomItemResponse(name: String): OwnedRoomItemResponse = OwnedRoomItemResponse(
    ownedRoomItemId = this.ownedRoomItemId,
    name = name,
    itemId = this.itemId,
    isEquipped = this.isEquipped,
)

fun EquippedRoomItemResult.toEquippedRoomItemResponse(): EquippedRoomItemResponse = EquippedRoomItemResponse(
    ownedRoomItemId = this.ownedRoomItemId,
    category = this.category,
    itemId = this.itemId,
    x = this.x,
    y = this.y,
    zIndex = this.zIndex,
    scale = this.scale,
)

fun DefaultRoomItemProperties.toOwnedRoomItem(userId: Long): OwnedRoomItem =
    OwnedRoomItem(userId = userId, itemId = this.itemId).apply {
        isEquipped = true
        x = this@toOwnedRoomItem.x
        y = this@toOwnedRoomItem.y
        zIndex = this@toOwnedRoomItem.zIndex
        scale = this@toOwnedRoomItem.scale
    }

@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.room.dto

import com.dandi.nyummy.room.enum.RoomItemCategory
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive

data class RoomResponse(
    val categories: List<RoomItemCategoryResponse>,
    val equippedRoomItems: List<EquippedRoomItemResponse>,
)

data class RoomItemCategoryResponse(
    val category: RoomItemCategory,
    val categoryName: String,
    val ownedRoomItems: List<OwnedRoomItemResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

data class OwnedRoomItemsResponse(
    val ownedRoomItems: List<OwnedRoomItemResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

data class OwnedRoomItemResponse(val roomItemId: Long, val name: String, val imageUrl: String, val isEquipped: Boolean)

data class EquippedRoomItemResponse(
    val roomItemId: Long,
    val category: RoomItemCategory,
    val imageUrl: String,
    val x: Float,
    val y: Float,
    val zIndex: Int,
    val scale: Float,
)

data class UpdateEquippedRoomItemsRequest(
    @field:Valid
    val equippedRoomItems: List<EquippedRoomItemRequest>,
)

data class EquippedRoomItemRequest(
    val roomItemId: Long,
    val x: Float,
    val y: Float,
    val zIndex: Int,

    @field:Positive(message = "scale은 0보다 커야 합니다.")
    val scale: Float,
)

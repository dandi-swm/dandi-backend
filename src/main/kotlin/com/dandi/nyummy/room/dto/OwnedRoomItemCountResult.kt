package com.dandi.nyummy.room.dto

/** 아이템 하나를 사용자가 가진 개수. 같은 아이템을 여러 개 사면 보유 아이템이 각각 따로라 센다. */
data class OwnedRoomItemCountResult(val itemId: Long, val ownedCount: Long)

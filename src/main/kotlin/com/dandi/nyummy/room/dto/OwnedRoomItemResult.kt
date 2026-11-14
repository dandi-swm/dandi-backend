package com.dandi.nyummy.room.dto

/**
 * 보유 아이템 목록 조회 결과 한 건. 상품 이름은 items에서 함께 읽는다.
 *
 * @property nameTranslations 아이템 이름의 번역([com.dandi.nyummy.item.entity.Item.nameTranslations])
 */
data class OwnedRoomItemResult(
    val ownedRoomItemId: Long,
    val name: String,
    val nameTranslations: Map<String, String>?,
    val itemId: Long,
    val isEquipped: Boolean,
)

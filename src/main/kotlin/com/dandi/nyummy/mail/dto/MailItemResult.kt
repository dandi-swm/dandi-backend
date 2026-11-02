package com.dandi.nyummy.mail.dto

/** 우편에 든 아이템 조회 결과 한 줄. 이름은 items에서 함께 읽는다. */
data class MailItemResult(
    val mailId: Long,
    val itemId: Long,
    val name: String,
    val quantity: Int,
    val itemPurchaseLineId: Long? = null,
)

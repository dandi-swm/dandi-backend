package com.dandi.nyummy.mail.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 우편에 든 아이템 한 줄. 받으면 [quantity]만큼 보유 아이템이 생긴다.
 *
 * @property itemPurchaseLineId 구매한 구성품이면 그 id이고, 이때 [quantity]는 1이다. 보상이면 null이다
 */
@Entity
@Table(name = "mail_items")
class MailItem(
    @Column(name = "mail_id", nullable = false)
    val mailId: Long,

    @Column(name = "item_id", nullable = false)
    val itemId: Long,

    @Column(name = "quantity", nullable = false)
    val quantity: Int,

    @Column(name = "item_purchase_line_id")
    val itemPurchaseLineId: Long? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L
}

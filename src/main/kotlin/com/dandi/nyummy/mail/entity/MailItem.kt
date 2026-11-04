package com.dandi.nyummy.mail.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 우편에 든 아이템 한 줄. 받으면 [quantity]만큼 보유 아이템이 생긴다.
 *
 * @property itemPurchaseLineId 구매한 구성품이면 그 id이고, 이때 [quantity]는 1이다. 보상이면 null이다
 * @property revokedAt 받기 전에 환불되어 회수한 시각. 받기에서 이 아이템을 건너뛴다
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

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    fun revoke(revokedAt: Instant) {
        this.revokedAt = revokedAt
    }
}

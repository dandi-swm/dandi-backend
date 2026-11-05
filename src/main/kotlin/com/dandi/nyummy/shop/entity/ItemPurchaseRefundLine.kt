package com.dandi.nyummy.shop.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 환불에 포함된 구성품 하나. 행이 있으면 그 구성품은 환불된 것이고, 같은 구성품은 두 번 환불할 수 없다.
 * 추가만 하고 수정·삭제하지 않는다.
 */
@Entity
@Table(name = "item_purchase_refund_lines")
class ItemPurchaseRefundLine(
    @Column(name = "item_purchase_refund_id", nullable = false)
    val itemPurchaseRefundId: Long,

    @Column(name = "item_purchase_line_id", nullable = false)
    val itemPurchaseLineId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L
}

package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.ItemPurchaseRefund
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ItemPurchaseRefundRepository : JpaRepository<ItemPurchaseRefund, Long> {
    fun findByItemPurchaseProductId(itemPurchaseProductId: Long): List<ItemPurchaseRefund>

    fun findByItemPurchaseProductIdInOrderByIdAsc(itemPurchaseProductIds: Collection<Long>): List<ItemPurchaseRefund>
}

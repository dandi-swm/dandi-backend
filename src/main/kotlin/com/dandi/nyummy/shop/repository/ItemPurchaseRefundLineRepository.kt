package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.ItemPurchaseRefundLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ItemPurchaseRefundLineRepository : JpaRepository<ItemPurchaseRefundLine, Long> {
    fun findByItemPurchaseLineIdIn(itemPurchaseLineIds: Collection<Long>): List<ItemPurchaseRefundLine>
}

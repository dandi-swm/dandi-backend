package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.ItemPurchaseLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ItemPurchaseLineRepository : JpaRepository<ItemPurchaseLine, Long> {
    fun findByItemPurchaseProductId(itemPurchaseProductId: Long): List<ItemPurchaseLine>
}

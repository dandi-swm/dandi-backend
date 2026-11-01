package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.ItemPurchaseProduct
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ItemPurchaseProductRepository : JpaRepository<ItemPurchaseProduct, Long> {
    fun findByItemPurchaseId(itemPurchaseId: Long): List<ItemPurchaseProduct>
}

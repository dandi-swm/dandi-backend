package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.ItemPurchase
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ItemPurchaseRepository : JpaRepository<ItemPurchase, Long> {
    fun findByUserIdAndIdempotencyKey(userId: Long, idempotencyKey: String): ItemPurchase?

    fun existsByUserId(userId: Long): Boolean
}

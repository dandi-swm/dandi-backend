package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.ItemPurchaseAttempt
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ItemPurchaseAttemptRepository : JpaRepository<ItemPurchaseAttempt, Long>

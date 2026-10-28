package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.entity.PromotionProductId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PromotionProductRepository : JpaRepository<PromotionProduct, PromotionProductId>

package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.Promotion
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PromotionRepository : JpaRepository<Promotion, Long>

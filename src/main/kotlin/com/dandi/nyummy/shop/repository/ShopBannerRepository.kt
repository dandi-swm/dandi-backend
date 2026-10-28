package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.ShopBanner
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ShopBannerRepository : JpaRepository<ShopBanner, Long>

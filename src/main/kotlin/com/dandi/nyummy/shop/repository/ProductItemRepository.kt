package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.ProductItem
import com.dandi.nyummy.shop.entity.ProductItemId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ProductItemRepository : JpaRepository<ProductItem, ProductItemId>

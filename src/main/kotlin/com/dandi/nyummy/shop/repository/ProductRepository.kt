package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.Product
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ProductRepository : JpaRepository<Product, Long>

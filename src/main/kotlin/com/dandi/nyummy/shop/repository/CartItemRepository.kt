package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.CartItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CartItemRepository : JpaRepository<CartItem, Long> {
    /** 사용자의 장바구니를 담은 시각 최신순으로 조회한다. 시각이 같으면 나중에 담은 것(id 내림차순)이 앞이다. */
    fun findByUserIdOrderByCreatedAtDescIdDesc(userId: Long): List<CartItem>

    /** 장바구니에 담긴 상품 종류 수(상단 배지) */
    fun countByUserId(userId: Long): Long
}

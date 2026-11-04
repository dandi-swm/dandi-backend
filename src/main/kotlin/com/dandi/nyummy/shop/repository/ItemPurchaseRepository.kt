package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.ItemPurchase
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ItemPurchaseRepository : JpaRepository<ItemPurchase, Long> {
    fun findByUserIdAndIdempotencyKey(userId: Long, idempotencyKey: String): ItemPurchase?

    fun existsByUserId(userId: Long): Boolean

    /**
     * id가 [cursor]보다 작은 구매를 최신순(id 내림차순)으로 조회한다.
     *
     * 위치는 cursor 조건이 정하므로 [pageable]은 page 0으로 개수만 정한다.
     * Slice는 size + 1개를 읽어 다음 묶음이 있는지 판단한다.
     */
    fun findByUserIdAndIdLessThanOrderByIdDesc(userId: Long, cursor: Long, pageable: Pageable): Slice<ItemPurchase>
}

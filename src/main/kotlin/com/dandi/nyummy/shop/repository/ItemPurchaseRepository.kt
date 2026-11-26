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

    /**
     * 모든 사용자의 구매 중 id가 [cursor]보다 작은 구매를 최신순(id 내림차순)으로 조회한다. 어드민의 최근 구매 목록에 쓴다.
     *
     * PK 순서로 읽어 따로 인덱스가 필요 없다. [pageable]은 page 0으로 개수만 정한다.
     */
    fun findByIdLessThanOrderByIdDesc(cursor: Long, pageable: Pageable): Slice<ItemPurchase>
}

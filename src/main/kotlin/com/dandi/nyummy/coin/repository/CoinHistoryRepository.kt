package com.dandi.nyummy.coin.repository

import com.dandi.nyummy.coin.entity.CoinHistory
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CoinHistoryRepository : JpaRepository<CoinHistory, Long> {
    /**
     * id가 [cursor]보다 작은 내역을 최신순(id 내림차순)으로 조회한다. (user_id, id) 인덱스를 탄다.
     *
     * 위치는 cursor 조건이 정하므로 [pageable]은 page 0으로 개수만 정한다.
     * Slice는 size + 1개를 읽어 다음 묶음이 있는지 판단한다.
     */
    fun findByUserIdAndIdLessThanOrderByIdDesc(userId: Long, cursor: Long, pageable: Pageable): Slice<CoinHistory>

    fun existsByUserIdAndCategory(userId: Long, category: CoinHistoryCategory): Boolean
}

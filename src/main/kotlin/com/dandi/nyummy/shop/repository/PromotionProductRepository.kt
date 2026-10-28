package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.entity.PromotionProductId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface PromotionProductRepository : JpaRepository<PromotionProduct, PromotionProductId> {

    fun findByPromotionIdInOrderByDisplayOrderAsc(promotionIds: Collection<Long>): List<PromotionProduct>

    /**
     * 프로모션에 묶인 상품을 모두 지운다. 진열 목록을 통째로 바꿀 때 새 행을 넣기 전에 부른다.
     *
     * 파생 쿼리(deleteBy)는 지울 행을 읽어 두었다가 flush 때 지우는데, Hibernate는 flush에서 insert를 delete보다 먼저 하므로
     * 같은 키로 다시 넣으면 PK가 겹친다. 그래서 JPQL로 바로 지우고, 영속성 컨텍스트에 남은 옛 행도 비운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PromotionProduct as pp where pp.promotionId = :promotionId")
    fun deleteByPromotionId(@Param("promotionId") promotionId: Long): Int
}

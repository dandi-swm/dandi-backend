package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.enum.PromotionCategory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface PromotionRepository : JpaRepository<Promotion, Long> {

    /**
     * [now]에 진행 중인 프로모션(startsAt <= now < endsAt, 종료 시각이 없으면 계속 진행)을 모두 조회한다.
     * 사용자가 대상인지는 서비스가 가른다.
     */
    @Query(
        """
            select p
            from Promotion p
            where p.startsAt <= :now
                and (p.endsAt is null or p.endsAt > :now)
        """,
    )
    fun findActivePromotions(@Param("now") now: Instant): List<Promotion>

    /**
     * 기간 [startsAt, endsAt)과 조금이라도 겹치는 [category] 프로모션이 있는지 확인한다. 종료 시각이 없으면 계속 진행으로 본다.
     */
    @Query(
        """
            select count(p) > 0
            from Promotion p
            where p.category = :category
                and p.startsAt < :endsAt
                and (p.endsAt is null or p.endsAt > :startsAt)
        """,
    )
    fun existsPromotionInPeriod(
        @Param("category") category: PromotionCategory,
        @Param("startsAt") startsAt: Instant,
        @Param("endsAt") endsAt: Instant,
    ): Boolean
}

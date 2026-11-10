package com.dandi.nyummy.cat.repository

import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.internal.batch.dto.RetentionTargetResult
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CatRepository : JpaRepository<Cat, Long> {
    fun findByUserId(userId: Long): Cat?

    fun existsByUserId(userId: Long): Boolean

    @Query(
        """
            select new com.dandi.nyummy.internal.batch.dto.RetentionTargetResult(c.userId, c.name, c.lastMealAt, d.token, d.language)
            from Cat as c
                join Profile as p on p.userId = c.userId
                join DeviceToken as d on d.userId = c.userId
            where p.isMarketingPushEnabled = true
        """,
    )
    fun findRetentionPushTargets(): List<RetentionTargetResult>
}

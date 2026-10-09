package com.dandi.nyummy.cat.repository

import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.internal.batch.dto.RetentionTarget
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CatRepository : JpaRepository<Cat, Long> {
    fun findByUserId(userId: Long): Cat?

    fun existsByUserId(userId: Long): Boolean

    @Query(
        """
            select new com.dandi.nyummy.internal.batch.dto.RetentionTarget(c.userId, c.name, c.lastMealAt, d.token)
            from Cat as c
                join Profile as p on p.userId = c.userId
                join DeviceToken as d on d.userId = c.userId
            where p.isMarketingPushEnabled = true
        """,
    )
    fun getRetentionPushTargets(): List<RetentionTarget>
}

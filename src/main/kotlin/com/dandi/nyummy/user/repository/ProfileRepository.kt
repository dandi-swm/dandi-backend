package com.dandi.nyummy.user.repository

import com.dandi.nyummy.internal.batch.dto.MealReminderTargetResult
import com.dandi.nyummy.user.entity.Profile
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProfileRepository : JpaRepository<Profile, Long> {

    fun findProfileByUserId(userId: Long): Profile?

    // SELECT … FOR UPDATE. 같은 사용자의 작업을 이 행 하나로 줄 세울 때 쓴다(트랜잭션이 끝날 때까지 잠긴다).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findProfileWithLockByUserId(userId: Long): Profile?

    @Query(
        """
            select new com.dandi.nyummy.internal.batch.dto.MealReminderTargetResult(
                p.userId, c.name, p.breakfastHour, p.lunchHour, p.dinnerHour, d.token, d.language
            )
            from Profile as p
                join Cat as c on c.userId = p.userId
                join DeviceToken as d on d.userId = p.userId
            where p.isServicePushEnabled = true
                and (p.breakfastHour = :hour or p.lunchHour = :hour or p.dinnerHour = :hour)
        """,
    )
    fun findMealReminderTargets(@Param("hour") hour: Int): List<MealReminderTargetResult>
}

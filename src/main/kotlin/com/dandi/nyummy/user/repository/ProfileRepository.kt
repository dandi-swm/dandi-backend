package com.dandi.nyummy.user.repository

import com.dandi.nyummy.internal.batch.dto.MealReminderTarget
import com.dandi.nyummy.user.entity.Profile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProfileRepository : JpaRepository<Profile, Long> {

    fun getProfileByUserId(userId: Long): Profile?

    @Query(
        """
            select new com.dandi.nyummy.internal.batch.dto.MealReminderTarget(
                p.userId, c.name, p.breakfastHour, p.lunchHour, p.dinnerHour, d.token
            )
            from Profile as p
                join Cat as c on c.userId = p.userId
                join DeviceToken as d on d.userId = p.userId
            where p.isServicePushEnabled = true
                and (p.breakfastHour = :hour or p.lunchHour = :hour or p.dinnerHour = :hour)
        """,
    )
    fun getMealReminderTargets(@Param("hour") hour: Int): List<MealReminderTarget>
}

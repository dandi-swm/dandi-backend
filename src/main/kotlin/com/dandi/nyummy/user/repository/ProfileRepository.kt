package com.dandi.nyummy.user.repository

import com.dandi.nyummy.user.entity.Profile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProfileRepository : JpaRepository<Profile, Long> {

    fun getProfileByUserId(userId: Long): Profile?

    @Query(
        """
            select p
            from Profile as p
            where p.breakfastHour = :hour or p.lunchHour = :hour or p.dinnerHour = :hour
        """,
    )
    fun findNotificationTargetsByHour(@Param("hour") hour: Int): List<Profile>
}

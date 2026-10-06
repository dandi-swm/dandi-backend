package com.dandi.nyummy.user.repository

import com.dandi.nyummy.user.entity.Profile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProfileRepository : JpaRepository<Profile, Long> {

    fun getProfileByUserId(userId: Long): Profile?

    // 일단 동작하게만 구현하고 추후 부하 테스트 때 쿼리 개선 예정!
    @Query(
        """
            select p
            from Profile as p
            where p.breakfastHour = :hour or p.lunchHour = :hour or p.dinnerHour = :hour
        """,
    )
    fun findNotificationTargetsByHour(@Param("hour") hour: Int): List<Profile>
}

package com.dandi.nyummy.user.repository

import com.dandi.nyummy.internal.batch.dto.MealReminderTargetResult
import com.dandi.nyummy.user.entity.Profile
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProfileRepository : JpaRepository<Profile, Long> {

    fun findProfileByUserId(userId: Long): Profile?

    // SELECT … FOR UPDATE. 같은 사용자의 작업을 이 행 하나로 줄 세울 때 쓴다(트랜잭션이 끝날 때까지 잠긴다).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findProfileWithLockByUserId(userId: Long): Profile?

    /**
     * user_id가 [cursor]보다 큰 식사 리마인더 대상을 user_id 오름차순으로 조회한다.
     *
     * 위치는 cursor 조건이 정하므로 [pageable]은 page 0으로 개수만 정한다.
     * Slice는 size + 1개를 읽어 다음 묶음이 있는지 판단한다.
     *
     * 사용자당 한 행이라(기기 토큰·고양이가 user_id 유니크) user_id를 커서로 쓴다.
     * 한 사용자에 여러 행이 생기면 묶음 경계에서 그 사용자의 나머지 행이 `user_id > cursor`에 걸려 빠진다.
     */
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
                and p.userId > :cursor
            order by p.userId
        """,
    )
    fun findMealReminderTargetsAfterCursor(
        @Param("hour") hour: Int,
        @Param("cursor") cursor: Long,
        pageable: Pageable,
    ): Slice<MealReminderTargetResult>
}

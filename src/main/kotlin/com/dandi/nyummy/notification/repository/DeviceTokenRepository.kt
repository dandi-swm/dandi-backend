package com.dandi.nyummy.notification.repository

import com.dandi.nyummy.admin.dto.AppUpdatePushTargetResult
import com.dandi.nyummy.notification.entity.DeviceToken
import com.dandi.nyummy.notification.enum.DevicePlatform
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DeviceTokenRepository : JpaRepository<DeviceToken, Long> {
    fun findDeviceTokenByUserId(userId: Long): DeviceToken?

    fun findDeviceTokensByTokenIn(tokens: Collection<String>): List<DeviceToken>

    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceToken d where d.token = :token and d.userId <> :userId")
    fun deleteByTokenAndNotUserId(@Param("token") token: String, @Param("userId") userId: Long): Int

    fun deleteByUserId(userId: Long)

    /**
     * 서비스 알림을 받기로 한 사용자의 기기 토큰. 수신 거부거나 토큰이 없으면 null이다.
     *
     * 수신 여부를 쿼리에서 거르는 이유는 호출부가 어느 플래그를 봐야 하는지 몰라도 되게 하려는
     * 것이다. 마케팅 알림은 대상을 배치가 뽑으므로 여기에 대응 메서드를 두지 않는다.
     */
    @Query(
        """
            select d.token
            from DeviceToken as d
                join Profile as p on p.userId = d.userId
            where d.userId = :userId
                and p.isServicePushEnabled = true
        """,
    )
    fun findServicePushToken(@Param("userId") userId: Long): String?

    /**
     * user_id가 [cursor]보다 큰 앱 업데이트 알림 대상을 user_id 오름차순으로 조회한다. 서비스 알림을 켠 그 플랫폼 사용자다.
     *
     * 위치는 cursor 조건이 정하므로 [pageable]은 page 0으로 개수만 정한다.
     * 기기 토큰은 user_id 유니크라 사용자당 한 행이고, user_id를 커서로 쓸 수 있다.
     */
    @Query(
        """
            select new com.dandi.nyummy.admin.dto.AppUpdatePushTargetResult(d.userId, d.token, d.language)
            from DeviceToken as d
                join Profile as p on p.userId = d.userId
            where p.isServicePushEnabled = true
                and d.platform = :platform
                and d.userId > :cursor
            order by d.userId
        """,
    )
    fun findAppUpdatePushTargetsAfterCursor(
        @Param("platform") platform: DevicePlatform,
        @Param("cursor") cursor: Long,
        pageable: Pageable,
    ): Slice<AppUpdatePushTargetResult>

    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceToken d where d.token in :tokens")
    fun deleteByTokenIn(@Param("tokens") tokens: Collection<String>): Int
}

package com.dandi.nyummy.notification.repository

import com.dandi.nyummy.notification.entity.DeviceToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DeviceTokenRepository : JpaRepository<DeviceToken, Long> {
    fun getDeviceTokenByUserId(userId: Long): DeviceToken?

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
    fun getServicePushToken(@Param("userId") userId: Long): String?

    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceToken d where d.token in :tokens")
    fun deleteByTokenIn(@Param("tokens") tokens: Collection<String>): Int
}

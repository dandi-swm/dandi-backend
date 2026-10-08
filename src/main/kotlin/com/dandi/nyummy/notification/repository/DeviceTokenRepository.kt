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

    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceToken d where d.token in :tokens")
    fun deleteByTokenIn(@Param("tokens") tokens: Collection<String>): Int
}

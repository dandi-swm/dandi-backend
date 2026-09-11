package com.dandi.nyummy.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "refresh_token")
class RefreshToken(

    @Column(name = "refresh_token", nullable = false, length = 512)
    var refreshToken: String,

    @Column(name = "absolute_expires_at", nullable = false)
    var absoluteExpiresAt: Instant,

    @Column(name = "user_id", unique = true, nullable = false)
    val userId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    /**
     * 재발급 시 토큰만 교체한다. 절대 만료는 세션이 시작된 시점 기준이므로 건드리지 않는다.
     */
    fun rotate(refreshToken: String) {
        this.refreshToken = refreshToken
    }

    /**
     * 로그인으로 세션을 다시 시작한다. 토큰을 교체하고 절대 만료도 새로 찍는다.
     *
     * user_id가 UNIQUE라 사용자당 행이 하나뿐이므로, 재로그인 시 기존 행을 지우지 않고 이 메서드로 갱신한다.
     */
    fun restart(refreshToken: String, absoluteExpiresAt: Instant) {
        this.refreshToken = refreshToken
        this.absoluteExpiresAt = absoluteExpiresAt
    }

    /**
     * 절대 만료가 지났는지 확인한다. 지났다면 재발급이 불가능해 다시 로그인해야 한다.
     */
    fun isAbsoluteExpired(now: Instant): Boolean = absoluteExpiresAt.isBefore(now)
}

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

    // 재발급: 토큰만 교체, 절대 만료는 그대로
    fun rotate(refreshToken: String) {
        this.refreshToken = refreshToken
    }

    // 로그인/회원가입: 새 세션 시작 -> 90일
    fun restart(refreshToken: String, absoluteExpiresAt: Instant) {
        this.refreshToken = refreshToken
        this.absoluteExpiresAt = absoluteExpiresAt
    }

    fun isAbsoluteExpired(now: Instant): Boolean = absoluteExpiresAt.isBefore(now)
}

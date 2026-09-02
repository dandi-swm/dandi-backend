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

    @Column(name = "user_id", unique = true, nullable = false)
    val userId: Long,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    fun rotate(refreshToken: String, newExpiresAt: Instant) {
        this.refreshToken = refreshToken
        this.expiresAt = newExpiresAt
    }
}

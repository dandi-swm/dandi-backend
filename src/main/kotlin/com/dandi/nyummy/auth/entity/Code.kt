package com.dandi.nyummy.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "code")
class Code(

    @Column(name = "email", nullable = false, unique = true)
    val email: String,

    @Column(name = "code", nullable = false)
    var code: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @Column(name = "send_count", nullable = false)
    var sendCount: Int = 0

    fun updateCode(code: String) {
        this.code = code
    }

    fun updateExpiresAt(expiresAt: Instant) {
        this.expiresAt = expiresAt
    }

    fun resetSendCount() {
        this.sendCount = 0
    }

    fun resetAttemptCount() {
        this.attemptCount = 0
    }

    fun increaseSendCount() {
        this.sendCount += 1
    }

    fun increaseAttemptCount() {
        this.attemptCount += 1
    }
}

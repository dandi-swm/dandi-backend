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
@Table(name = "email_verification")
@EntityListeners(AuditingEntityListener::class)
class Mail(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0,

    @Column(name = "email", nullable = false, unique = true)
    val email: String,

    @Column(name = "code", nullable = false)
    var code: String,

    @Column(name = "is_verified", nullable = false)
    var isVerified: Boolean = false,

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

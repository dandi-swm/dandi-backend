package com.dandi.nyummy.user.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import java.time.Instant

@Entity
@Table(name = "users")
class User(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L,

    @Column(name = "email", nullable = false, unique = true, length = 255)
    val email: String,

    @Column(name = "password", nullable = false, length = 255)
    val password: String,

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)

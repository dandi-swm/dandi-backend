package com.dandi.nyummy.profile.entity

import com.dandi.nyummy.profile.enum.Gender
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.time.LocalDate

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "profile")
class Profile(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L,

    @Column(name = "nickname", length = 100)
    val nickname: String? = null,

    @Column(name = "birth")
    val birth: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    val gender: Gender? = null,

    @Column(name = "height")
    val height: Int? = null,

    @Column(name = "weight")
    val weight: Int? = null,

    @Column(name = "coin", nullable = false)
    val coin: Int = 0,

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long = 0L,

    @LastModifiedDate
    @Column(name = "updated_at")
    val updatedAt: Instant? = null,

    @Column(name = "last_login_at")
    val lastLoginAt: Instant? = null,
)

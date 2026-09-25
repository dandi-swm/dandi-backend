package com.dandi.nyummy.user.entity

import com.dandi.nyummy.auth.enum.AuthProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "users")
class User(

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    val provider: AuthProvider,

    @Column(name = "provider_user_id", length = 255)
    val providerUserId: String? = null,

    @Column(name = "email", length = 255)
    val email: String? = null,

    @Column(name = "password", length = 255)
    var password: String? = null,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "is_temp_password", nullable = false)
    var isTempPassword: Boolean = false

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    fun updateTempPassword(password: String) {
        this.password = password
        this.isTempPassword = true
    }
}

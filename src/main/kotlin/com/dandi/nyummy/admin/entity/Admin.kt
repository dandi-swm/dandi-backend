package com.dandi.nyummy.admin.entity

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
@Table(name = "admins")
class Admin(

    @Column(name = "email", nullable = false, length = 255)
    val email: String,

    @Column(name = "name", nullable = false, length = 50)
    var name: String,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "disabled_at")
    var disabledAt: Instant? = null

    val isDisabled: Boolean
        get() = disabledAt != null
}

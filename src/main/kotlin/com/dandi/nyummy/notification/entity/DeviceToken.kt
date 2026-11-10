package com.dandi.nyummy.notification.entity

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.notification.enum.DevicePlatform
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
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "device_tokens")
class DeviceToken(
    @Column(name = "token", nullable = false, length = 512, unique = true)
    var token: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20)
    var platform: DevicePlatform,

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,

    // 기기 앱이 지금 쓰는 언어. 요청 없이 보내는 푸시의 문구 언어를 정한다.
    // V0.53 전에 등록한 행은 NULL이고, 원본 언어로 본다. 앱이 다음에 토큰을 등록하면 채워진다.
    @Enumerated(EnumType.STRING)
    @Column(name = "language", length = 10)
    var language: Language?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    fun updateToken(token: String, platform: DevicePlatform) {
        this.token = token
        this.platform = platform
    }

    fun updateLanguage(language: Language) {
        this.language = language
    }
}

package com.dandi.nyummy.user.entity

import com.dandi.nyummy.user.enum.Gender
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

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "breakfast_hour")
    val breakfastHour: Int? = null

    @Column(name = "lunch_hour")
    val lunchHour: Int? = null

    @Column(name = "dinner_hour")
    val dinnerHour: Int? = null

    @Column(name = "coin", nullable = false)
    val coin: Int = 0

    @Column(name = "is_service_push_enabled", nullable = false)
    var isServicePushEnabled: Boolean = true

    @Column(name = "is_marketing_push_enabled", nullable = false)
    var isMarketingPushEnabled: Boolean = false

    @Column(name = "marketing_agreed_at")
    var marketingAgreedAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @Column(name = "last_login_at")
    val lastLoginAt: Instant? = null

    fun updateServicePushEnabled(isServicePushEnabled: Boolean) {
        this.isServicePushEnabled = isServicePushEnabled
    }

    /**
     * 마케팅 알림 수신 여부를 바꾼다. 거부에서 동의로 넘어갈 때만 동의 시각을 새로 찍는다.
     * 이미 동의한 상태에서 같은 값이 또 들어오면 시각을 건드리지 않는다
     */
    fun updateMarketingPushEnabled(isMarketingPushEnabled: Boolean, now: Instant) {
        if (isMarketingPushEnabled && !this.isMarketingPushEnabled) {
            this.marketingAgreedAt = now
        }

        this.isMarketingPushEnabled = isMarketingPushEnabled
    }
}

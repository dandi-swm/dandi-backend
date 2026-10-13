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
    var providerUserId: String? = null,

    @Column(name = "email", length = 255)
    var email: String? = null,

    @Column(name = "password", length = 255)
    var password: String? = null,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "is_temp_password", nullable = false)
    var isTempPassword: Boolean = false

    @Column(name = "withdrawn_at")
    var withdrawnAt: Instant? = null

    val isWithdrawn: Boolean
        get() = withdrawnAt != null

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    fun updateTempPassword(password: String) {
        this.password = password
        this.isTempPassword = true
    }

    fun updatePassword(password: String) {
        this.password = password
        this.isTempPassword = false
    }

    /**
     * 탈퇴 처리한다. 식별정보를 비우고 탈퇴 시각을 기록하며, 행 자체는 남긴다.
     *
     * 행을 남기는 이유는 meals · analysis_feedbacks가 user_id로 매달려 있어서다. 식별정보를 비우고
     * 프로필의 준식별자(Profile.anonymize)와 업로드 이미지(ImageService.deleteUserImages)까지 파기하면
     * 남은 데이터는 통계용 익명정보가 된다.
     *
     * email · providerUserId를 NULL로 두면 uk_users_provider_email / uk_users_provider_user_id의 슬롯이
     * 풀린다(MySQL 유니크 인덱스는 NULL을 서로 다른 값으로 본다). 그래서 같은 이메일로 바로 재가입할 수
     * 있고, 재가입은 새 users 행이라 과거 데이터와 연결되지 않는다.
     */
    fun withdraw(withdrawnAt: Instant) {
        this.email = null
        this.password = null
        this.providerUserId = null
        this.isTempPassword = false
        this.withdrawnAt = withdrawnAt
    }
}

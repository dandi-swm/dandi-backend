package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.language.enum.Language
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
@Table(name = "analysis_feedbacks")
class AnalysisFeedback(
    @Column(name = "rating", nullable = false)
    val rating: Float,

    @Column(name = "reason", length = 500)
    val reason: String? = null,

    @Column(name = "meal_id", nullable = false, unique = true)
    val mealId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    // 평가를 만든 요청의 앱 언어. 어드민이 평가를 언어별로 나눠 보는 데 쓴다.
    // V0.62 전 평가는 V0.63에서 기기 토큰 언어로 채웠다. 채우지 못한 행과 배포 중 구버전이 만든 행은 NULL이고, 원본 언어로 본다.
    @Enumerated(EnumType.STRING)
    @Column(name = "language", length = 10)
    val language: Language?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

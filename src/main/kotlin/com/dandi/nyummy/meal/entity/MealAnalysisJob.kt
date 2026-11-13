package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
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

/**
 * Worker가 가져갈 영양 분석 작업 한 건. 식사 하나에 하나이고, 끝나면(완료·최종 실패) 행을 지운다.
 *
 * lease는 DB 시계(NOW())로 정하고 비교하므로 엔티티로 바꾸지 않고 리포지토리 쿼리로만 바꾼다.
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "meal_analysis_jobs")
class MealAnalysisJob(
    @Column(name = "meal_id", nullable = false, unique = true)
    val mealId: Long,

    // 작업을 만든 요청의 앱 언어. Worker가 결과와 완료 푸시를 이 언어로 만든다.
    // V0.54 전에 만든 행과 배포 중 구버전이 만든 행은 NULL이고, 원본 언어로 본다.
    @Enumerated(EnumType.STRING)
    @Column(name = "language", length = 10)
    val language: Language?,

    // v2 식사의 작업 종류. null이면 v1 분석 작업이다(v1 API가 만든 작업과 V0.55 전에 만든 작업).
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    val category: MealAnalysisJobCategory? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    // NULL이면 대기, NOW()보다 이르면 lease가 만료된 작업이다.
    @Column(name = "lease_expires_at")
    val leaseExpiresAt: Instant? = null

    // 작업을 가져갈 수 있게 되는 시각. 만들 때 미룬 작업에만 있고, 바로 가져갈 수 있는 작업은 NULL이다.
    // lease_expires_at은 가져갈 때 덮이므로 따로 남긴다. 지표가 이 시각부터(없으면 createdAt부터) 기다린 시간을 센다.
    @Column(name = "available_at")
    val availableAt: Instant? = null

    // 선점할 때마다 1씩 오른다. 몇 번째 시도인지이자, 늦게 끝난 Worker의 쓰기를 막는 비교값이다.
    @Column(name = "lease_count", nullable = false)
    val leaseCount: Int = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

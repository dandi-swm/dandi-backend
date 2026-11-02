package com.dandi.nyummy.meal.entity

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
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    // NULL이면 대기, NOW()보다 이르면 lease가 만료된 작업이다.
    @Column(name = "lease_expires_at")
    val leaseExpiresAt: Instant? = null

    // 선점할 때마다 1씩 오른다. 몇 번째 시도인지이자, 늦게 끝난 Worker의 쓰기를 막는 비교값이다.
    @Column(name = "lease_count", nullable = false)
    val leaseCount: Int = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

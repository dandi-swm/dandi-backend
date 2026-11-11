package com.dandi.nyummy.report.entity

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

/**
 * Worker가 가져갈 리포트 생성 작업 한 건. 리포트 하나에 하나이고, 구매 트랜잭션에서 리포트와 함께 만든다.
 * 끝나거나(완료·최종 실패) 리포트가 취소되면 행을 지운다.
 *
 * lease는 DB 시계(NOW())로 정하고 비교하므로 엔티티로 바꾸지 않고 리포지토리 쿼리로만 바꾼다.
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "report_generation_jobs")
class ReportGenerationJob(
    @Column(name = "report_id", nullable = false, unique = true)
    val reportId: Long,

    // 작업을 만든 요청의 앱 언어. Worker가 결과와 완료 푸시를 이 언어로 만든다.
    // V0.54 전에 만든 행과 배포 중 구버전이 만든 행은 NULL이고, 원본 언어로 본다.
    @Enumerated(EnumType.STRING)
    @Column(name = "language", length = 10)
    val language: Language?,
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

package com.dandi.nyummy.report.entity

import com.dandi.nyummy.report.enum.ReportPurchaseOutcome
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
import java.time.LocalDate

/**
 * 리포트 구매 시도 기록. 성공·거부 모두 한 행이고, 추가만 하고 수정·삭제하지 않는다.
 *
 * @property requestedStartDate 앱이 보낸 startDate. 일요일이 아니어도 그대로 남긴다
 * @property requestedPrice 앱이 보낸 화면 가격
 * @property reportPurchaseId 만들거나 다시 돌려준 구매. 거부되었으면 null이다
 * @property appVersion 앱 버그와 조작된 요청을 구분하는 데 쓴다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "report_purchase_attempts")
class ReportPurchaseAttempt(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "requested_start_date", nullable = false)
    val requestedStartDate: LocalDate,

    @Column(name = "requested_price", nullable = false)
    val requestedPrice: Int,

    @Column(name = "idempotency_key", nullable = false, length = 64)
    val idempotencyKey: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    val outcome: ReportPurchaseOutcome,

    @Column(name = "report_purchase_id")
    val reportPurchaseId: Long? = null,

    @Column(name = "app_version", length = 20)
    val appVersion: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

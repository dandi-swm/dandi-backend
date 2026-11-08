@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.report.dto

import com.dandi.nyummy.report.enum.ReportStatus
import jakarta.validation.constraints.Min
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate

/**
 * @property startDate 받을 주의 일요일(Asia/Seoul 날짜)
 * @property purchasePrice 화면에서 본 가격. 서버는 이 값으로 차감하지 않고, 지금 가격과 같은지만 확인한다
 */
data class CreateReportPurchaseRequest(
    val startDate: LocalDate,

    @field:Min(value = 0, message = "purchasePrice는 0 이상이어야 합니다.")
    val purchasePrice: Int,
)

/**
 * 구매 결과. 리포트는 비동기로 만들어지고, 지금 상태는 목록·상세로 본다.
 *
 * @property reportId 같은 Idempotency-Key로 다시 보내면 같은 리포트가 온다
 * @property status 항상 WAITING. 같은 키로 처음 결과를 돌려줄 때도 처음 응답 그대로다
 * @property purchasePrice 차감한 코인
 * @property balance 구매 후 잔액
 */
data class ReportPurchaseResponse(
    val reportId: Long,
    val status: ReportStatus,
    val purchasePrice: Int,
    val balance: Int,
)

/**
 * @property purchasePrice 지금 구매하면 낼 코인. 다음 묶음에서도 같은 값을 준다
 * @property minMealCount 그 주에 분석이 완료된 식사가 이만큼 있어야 살 수 있다
 */
data class ReportsResponse(
    val purchasePrice: Int,
    val minMealCount: Int,
    val reports: List<ReportSummaryResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

/**
 * @property title AI가 쓴 한 줄 요약. COMPLETED일 때만 있다
 * @property isUnread 완료됐지만 아직 상세를 열어 보지 않음
 * @property isRefunded 취소·생성 실패로 코인을 돌려줌. 무료로 산 리포트는 취소·실패해도 false다
 * @property createdAt 구매 시각
 */
data class ReportSummaryResponse(
    val reportId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: ReportStatus,
    val title: String?,
    val isUnread: Boolean,
    val isRefunded: Boolean,
    val createdAt: Instant,
)

/**
 * @property completedAt 생성 완료 시각. 화면의 "○시 기준"에 쓴다. 완료 전이면 null이다
 * @property viewType 앱이 리포트를 그릴 네이티브 뷰. 완료 전이면 null이다
 * @property content viewType이 정한 모양의 값. 완료 전이면 null이다
 */
data class ReportResponse(
    val reportId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: ReportStatus,
    val title: String?,
    val createdAt: Instant,
    val completedAt: Instant?,
    val viewType: String?,
    val content: JsonNode?,
)

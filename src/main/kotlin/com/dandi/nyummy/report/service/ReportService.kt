package com.dandi.nyummy.report.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.report.calculator.calculateReportPurchasePrice
import com.dandi.nyummy.report.config.ReportProperties
import com.dandi.nyummy.report.dto.ReportResponse
import com.dandi.nyummy.report.dto.ReportsResponse
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.report.mapper.toReportResponse
import com.dandi.nyummy.report.mapper.toReportSummaryResponse
import com.dandi.nyummy.report.repository.ReportPurchaseRefundRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRepository
import com.dandi.nyummy.report.repository.ReportRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Service
class ReportService(
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val reportProperties: ReportProperties,
    private val reportRepository: ReportRepository,
    private val reportPurchaseRepository: ReportPurchaseRepository,
    private val reportPurchaseRefundRepository: ReportPurchaseRefundRepository,
) {

    /**
     * 사용자의 리포트를 최신순으로 [size]개씩 조회하고, 지금 구매하면 낼 가격과 살 수 있는 최소 식사 수를 함께 준다.
     * 무한 스크롤용 cursor 방식이다.
     *
     * 가격은 잠금 없이 읽은 값이라, 그사이 다른 기기가 무료를 먼저 쓰면 구매가 priceChanged로 거부되고 앱은 목록을 다시 조회한다.
     *
     * @param cursor 직전 묶음의 마지막 reportId. null이면 가장 최신 리포트부터 조회한다
     */
    @Transactional(readOnly = true)
    fun getReports(userId: Long, cursor: Long?, size: Int): ReportsResponse {
        val reports = reportRepository.findReportsByUserIdBeforeCursor(
            userId = userId,
            cursor = cursor ?: Long.MAX_VALUE,
            pageable = PageRequest.of(0, size),
        )
        val refundedReportIds = if (reports.content.isEmpty()) {
            emptySet()
        } else {
            reportPurchaseRefundRepository.findRefundedReportIds(reports.content.map { it.id }).toSet()
        }

        return ReportsResponse(
            purchasePrice = calculateReportPurchasePrice(
                hasFreeReportPurchase = reportPurchaseRepository.existsFreeReportPurchase(
                    userId,
                    ReportStatus.ACTIVE_STATUSES,
                ),
                listPrice = reportProperties.listPrice,
            ),
            minMealCount = reportProperties.minMealCount,
            reports = reports.content.map { it.toReportSummaryResponse(isRefunded = it.id in refundedReportIds) },
            nextCursor = if (reports.hasNext()) reports.content.last().id else null,
            hasNext = reports.hasNext(),
        )
    }

    /**
     * 리포트 하나를 조회한다. 완료된 리포트를 처음 조회하면 읽음으로 기록해 목록의 isUnread가 false가 된다.
     *
     * @throws BusinessException [ReportErrorCode.REPORT_NOT_FOUND] 없는 리포트인 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 리포트인 경우
     */
    @Transactional
    fun getReport(userId: Long, reportId: Long): ReportResponse {
        val report = reportRepository.findByIdOrNull(reportId)
            ?: throw BusinessException(ReportErrorCode.REPORT_NOT_FOUND)

        report.validateOwnership(userId)
        report.updateReadAt(clock.instant())

        return report.toReportResponse(content = report.content?.let { objectMapper.readTree(it) })
    }
}

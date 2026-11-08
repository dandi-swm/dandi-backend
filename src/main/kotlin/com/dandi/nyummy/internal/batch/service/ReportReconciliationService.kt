package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.internal.batch.dto.ReportMismatchResponse
import com.dandi.nyummy.internal.batch.dto.ReportReconciliationResponse
import com.dandi.nyummy.report.config.ReportProperties
import com.dandi.nyummy.report.enum.ReportMismatchCategory
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.report.repository.ReportMismatchRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 리포트·구매·환불·코인 내역·생성 작업이 서로 맞는지 대조한다. 찾기만 하고 고치지 않는다.
 *
 * 구매·취소·Worker가 지키는 규칙(이중 환불 없음, 환불 뒤 결과 전달 없음, 끝난 리포트에 작업 없음)이 운영 데이터에서도
 * 지켜지는지 확인하는 마지막 그물이다. 고치는 일은 원인을 보고 사람이 한다.
 */
@Service
class ReportReconciliationService(
    private val reportMismatchRepository: ReportMismatchRepository,
    private val reportProperties: ReportProperties,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ReportReconciliationService::class.java)

        // 종류마다 응답·로그에 담을 최대 리포트 수
        private const val REPORT_ID_LIMIT = 100

        // 찾은 불일치 수. 같은 불일치는 고칠 때까지 실행마다 다시 센다.
        private const val MISMATCH_METER = "nyummy.report.reconciliation.mismatches"

        // 유료면 환불이 있어야 하는 상태
        private val REFUNDABLE_STATUSES = setOf(ReportStatus.CANCELED, ReportStatus.FAILED).map { it.name }
        private val IN_PROGRESS_STATUSES = ReportStatus.IN_PROGRESS_STATUSES.map { it.name }
    }

    /**
     * 모든 종류를 한 읽기 트랜잭션에서 대조한다. 같은 스냅샷을 보므로, 대조 중에 끝난 구매·취소가 종류마다 다르게 보이지 않는다.
     * 생성 기한([ReportProperties.generationTimeToLive])을 넘긴 리포트는 Worker가 멈췄거나 계속 미뤄지는 경우라 함께 찾는다.
     */
    @Transactional(readOnly = true)
    fun reconcileReports(): ReportReconciliationResponse {
        val checkedAt = clock.instant()
        val mismatches = listOf(
            ReportMismatchCategory.MISSING_REFUND to
                reportMismatchRepository.findReportIdsWithMissingRefund(REFUNDABLE_STATUSES, REPORT_ID_LIMIT),
            ReportMismatchCategory.UNEXPECTED_REFUND to
                reportMismatchRepository.findReportIdsWithUnexpectedRefund(REFUNDABLE_STATUSES, REPORT_ID_LIMIT),
            ReportMismatchCategory.REFUND_QUANTITY_MISMATCH to
                reportMismatchRepository.findReportIdsWithRefundQuantityMismatch(REPORT_ID_LIMIT),
            ReportMismatchCategory.PURCHASE_COIN_HISTORY_MISMATCH to
                reportMismatchRepository.findReportIdsWithPurchaseCoinHistoryMismatch(REPORT_ID_LIMIT),
            ReportMismatchCategory.REFUND_COIN_HISTORY_MISMATCH to
                reportMismatchRepository.findReportIdsWithRefundCoinHistoryMismatch(REPORT_ID_LIMIT),
            ReportMismatchCategory.ORPHAN_JOB to
                reportMismatchRepository.findReportIdsWithOrphanJob(IN_PROGRESS_STATUSES, REPORT_ID_LIMIT),
            ReportMismatchCategory.MISSING_JOB to
                reportMismatchRepository.findReportIdsWithMissingJob(IN_PROGRESS_STATUSES, REPORT_ID_LIMIT),
            ReportMismatchCategory.OVERDUE_GENERATION to
                reportMismatchRepository.findReportIdsWithOverdueGeneration(
                    IN_PROGRESS_STATUSES,
                    checkedAt.minus(reportProperties.generationTimeToLive),
                    REPORT_ID_LIMIT,
                ),
            ReportMismatchCategory.INCOMPLETE_COMPLETED to
                reportMismatchRepository.findReportIdsWithIncompleteCompleted(
                    ReportStatus.COMPLETED.name,
                    REPORT_ID_LIMIT,
                ),
        ).map { (category, reportIds) -> ReportMismatchResponse(category, reportIds) }

        mismatches.forEach { mismatch ->
            Counter.builder(MISMATCH_METER)
                .tag("category", mismatch.category.name.lowercase())
                .register(meterRegistry)
                .increment(mismatch.reportIds.size.toDouble())
            if (mismatch.reportIds.isNotEmpty()) {
                logger.error("리포트 불일치를 찾았습니다: category={}, reportIds={}", mismatch.category, mismatch.reportIds)
            }
        }

        val mismatchCount = mismatches.sumOf { it.reportIds.size }
        logger.info("리포트 대조 완료: mismatchCount={}", mismatchCount)
        return ReportReconciliationResponse(checkedAt, mismatchCount, mismatches)
    }
}

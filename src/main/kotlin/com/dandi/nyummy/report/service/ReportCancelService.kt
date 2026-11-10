package com.dandi.nyummy.report.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.report.dto.ReportCancelResponse
import com.dandi.nyummy.report.enum.ReportPurchaseRefundCategory
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.report.repository.ReportGenerationJobRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRefundRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRepository
import com.dandi.nyummy.report.repository.ReportRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 사용자의 리포트 취소. 생성이 끝나지 않은(WAITING·GENERATING) 리포트만 취소할 수 있고, 유료였으면 코인을 돌려준다.
 */
@Service
class ReportCancelService(
    private val clock: Clock,
    private val coinService: CoinService,
    private val reportPurchaseRefundService: ReportPurchaseRefundService,
    private val reportRepository: ReportRepository,
    private val reportGenerationJobRepository: ReportGenerationJobRepository,
    private val reportPurchaseRepository: ReportPurchaseRepository,
    private val reportPurchaseRefundRepository: ReportPurchaseRefundRepository,
) {

    /**
     * 리포트를 취소한다. 무료로 산 리포트였으면 돌려줄 코인 대신 무료 기회가 다시 생긴다(첫 무료 판정이 취소된 리포트를 세지 않는다).
     *
     * 잠그는 순서는 코인 → 작업 → 리포트로, 구매·Worker의 실패 환불과 같다. 리포트를 작업보다 먼저 잠그면 lease 트랜잭션
     * (작업 → 리포트)과 서로를 기다려 데드락이 난다.
     *
     * Worker와 겹치면 먼저 커밋한 쪽이 이긴다.
     * - 취소가 먼저면 작업이 지워져, 늦게 끝난 Worker의 확정·실패(번호 대조 삭제)가 0행이 되어 결과를 버린다.
     * - 확정이 먼저면 작업 삭제가 Worker의 커밋을 기다린 뒤 0행이 되고, 리포트가 이미 COMPLETED라 거부한다.
     * - Worker의 실패 환불은 코인 행을 먼저 잠그므로 취소와 차례로 처리되고, 뒤에 온 쪽은 리포트가 이미 끝나 있다.
     *
     * 이미 취소된 리포트를 다시 취소하면 처음 결과와 같은 응답을 준다(돌려준 코인은 그때 환불 기록, 잔액은 지금 값).
     *
     * @throws BusinessException [ReportErrorCode.REPORT_NOT_FOUND] 없는 리포트인 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 리포트인 경우
     * @throws BusinessException [ReportErrorCode.NOT_CANCELABLE] 이미 완료되었거나 생성에 실패한 리포트인 경우
     */
    @Transactional
    fun cancelReport(userId: Long, reportId: Long): ReportCancelResponse {
        val coin = coinService.lockCoin(userId)

        // 사용자와 주는 바뀌지 않는 값이라 잠그지 않고 읽는다. 상태는 아래에서 잠그며 다시 본다.
        val report = reportRepository.findByIdOrNull(reportId)
            ?: throw BusinessException(ReportErrorCode.REPORT_NOT_FOUND)
        report.validateOwnership(userId)
        // 취소는 같은 사용자의 코인 행 잠금 뒤에만 일어나므로, 잠금 뒤에 읽은 CANCELED는 믿을 수 있다.
        if (report.status == ReportStatus.CANCELED) {
            return replayReportCancel(reportId, coin)
        }

        // 작업은 PK로 지운다. report_id로 지우면 Worker의 결과 쓰기와 같은 행의 인덱스를 거꾸로 잠가 데드락이 난다.
        reportGenerationJobRepository.findReportGenerationJobIdByReportId(reportId)
            ?.let { reportGenerationJobRepository.deleteReportGenerationJobById(it) }
        val updatedCount = reportRepository.updateReportStatuses(
            listOf(reportId),
            ReportStatus.IN_PROGRESS_STATUSES,
            ReportStatus.CANCELED,
            clock.instant(),
        )
        if (updatedCount == 0) {
            val status = reportRepository.findReportStatusWithLockById(reportId)?.let { ReportStatus.valueOf(it) }
            if (status == ReportStatus.CANCELED) {
                return replayReportCancel(reportId, coin)
            }
            throw BusinessException(ReportErrorCode.NOT_CANCELABLE)
        }

        val refundedCoinQuantity = reportPurchaseRefundService.createReportPurchaseRefund(
            coin,
            reportId,
            ReportPurchaseRefundCategory.CANCELED,
        )

        return ReportCancelResponse(
            reportId = reportId,
            status = ReportStatus.CANCELED,
            refundedCoinQuantity = refundedCoinQuantity,
            // 환불이 같은 트랜잭션에서 잠근 코인 행(영속성 컨텍스트의 같은 엔티티)에 더했으므로 환불 뒤 잔액이다
            balance = coin.balance,
        )
    }

    private fun replayReportCancel(reportId: Long, coin: Coin): ReportCancelResponse {
        val refundedCoinQuantity = reportPurchaseRepository.findByReportId(reportId)
            ?.let { reportPurchaseRefundRepository.findByReportPurchaseId(it.id) }
            ?.coinQuantity
            ?: 0

        return ReportCancelResponse(
            reportId = reportId,
            status = ReportStatus.CANCELED,
            refundedCoinQuantity = refundedCoinQuantity,
            balance = coin.balance,
        )
    }
}

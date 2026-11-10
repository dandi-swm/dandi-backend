package com.dandi.nyummy.report.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.report.dto.CreateReportPurchaseRequest
import com.dandi.nyummy.report.dto.ReportPurchaseResponse
import com.dandi.nyummy.report.enum.ReportPurchaseOutcome
import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service

/**
 * 리포트 구매. 트랜잭션은 [ReportPurchaseTransactionService]가 열고, 여기서는 실패한 시도를 롤백 뒤에 기록한다.
 * 트랜잭션 안에서 기록하면 구매와 함께 롤백되어 남지 않는다.
 */
@Service
class ReportPurchaseService(
    private val reportPurchaseTransactionService: ReportPurchaseTransactionService,
    private val reportPurchaseAttemptService: ReportPurchaseAttemptService,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ReportPurchaseService::class.java)
    }

    /**
     * 한 주의 리포트를 산다. 거부 사유는 [ReportPurchaseTransactionService.createReportPurchase]를 본다.
     *
     * 잠금을 제시간에 얻지 못하거나 교착으로 롤백되면 앞선 요청의 결과를 알 수 없으므로 처리 중으로 응답한다.
     * 앱은 새 키를 만들지 않고 같은 키로 다시 보낸다.
     *
     * 거부 사유가 아닌 예외도 [ReportPurchaseOutcome.UNEXPECTED_ERROR]로 기록하고 그대로 던진다. 응답과 로그는 전역 예외 처리가 맡는다.
     *
     * @throws BusinessException [ReportErrorCode.PURCHASE_IN_PROGRESS] 코인 행 잠금을 제시간에 얻지 못하거나 교착으로 롤백된 경우
     */
    fun createReportPurchase(
        userId: Long,
        idempotencyKey: String,
        request: CreateReportPurchaseRequest,
        appVersion: String?,
    ): ReportPurchaseResponse {
        try {
            return reportPurchaseTransactionService.createReportPurchase(userId, idempotencyKey, request, appVersion)
        } catch (e: BusinessException) {
            createFailedAttempt(userId, idempotencyKey, request, appVersion, ReportPurchaseOutcome.from(e.errorCode))
            throw e
        } catch (e: CannotAcquireLockException) {
            logger.warn("리포트 구매 잠금을 얻지 못했습니다: userId={}", userId, e)
            createFailedAttempt(userId, idempotencyKey, request, appVersion, ReportPurchaseOutcome.PURCHASE_IN_PROGRESS)
            throw BusinessException(ReportErrorCode.PURCHASE_IN_PROGRESS)
        } catch (e: RuntimeException) {
            createFailedAttempt(userId, idempotencyKey, request, appVersion, ReportPurchaseOutcome.UNEXPECTED_ERROR)
            throw e
        }
    }

    // 기록이 실패해도 사용자에게는 원래 실패를 그대로 응답한다
    private fun createFailedAttempt(
        userId: Long,
        idempotencyKey: String,
        request: CreateReportPurchaseRequest,
        appVersion: String?,
        outcome: ReportPurchaseOutcome,
    ) {
        try {
            reportPurchaseAttemptService.createReportPurchaseAttempt(
                userId,
                idempotencyKey,
                request,
                appVersion,
                outcome,
            )
        } catch (e: DataAccessException) {
            logger.error("리포트 구매 시도를 기록하지 못했습니다: userId={}, outcome={}", userId, outcome, e)
        }
    }
}

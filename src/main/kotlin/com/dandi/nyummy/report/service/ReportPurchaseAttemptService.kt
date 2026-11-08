package com.dandi.nyummy.report.service

import com.dandi.nyummy.report.dto.CreateReportPurchaseRequest
import com.dandi.nyummy.report.entity.ReportPurchaseAttempt
import com.dandi.nyummy.report.enum.ReportPurchaseOutcome
import com.dandi.nyummy.report.repository.ReportPurchaseAttemptRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 리포트 구매 시도를 기록한다. 성공은 구매 트랜잭션 안에서, 거부는 그 트랜잭션이 롤백된 뒤 따로 기록한다.
 */
@Service
class ReportPurchaseAttemptService(private val reportPurchaseAttemptRepository: ReportPurchaseAttemptRepository) {
    companion object {
        private const val APP_VERSION_MAX_LENGTH = 20
    }

    /**
     * 앱이 보낸 요청을 그대로 남긴다. 앱 버전이 컬럼 길이를 넘으면 null로 둔다. 부가 정보 때문에 기록이 실패하지 않게 하기 위함이다.
     *
     * @param reportPurchaseId 만들거나 다시 돌려준 구매. 거부되었으면 null이다
     */
    @Transactional
    fun createReportPurchaseAttempt(
        userId: Long,
        idempotencyKey: String,
        request: CreateReportPurchaseRequest,
        appVersion: String?,
        outcome: ReportPurchaseOutcome,
        reportPurchaseId: Long? = null,
    ) {
        reportPurchaseAttemptRepository.save(
            ReportPurchaseAttempt(
                userId = userId,
                requestedStartDate = request.startDate,
                requestedPrice = request.purchasePrice,
                idempotencyKey = idempotencyKey,
                outcome = outcome,
                reportPurchaseId = reportPurchaseId,
                appVersion = appVersion?.takeIf { it.length <= APP_VERSION_MAX_LENGTH },
            ),
        )
    }
}

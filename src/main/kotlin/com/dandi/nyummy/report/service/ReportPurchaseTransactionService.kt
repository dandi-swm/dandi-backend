package com.dandi.nyummy.report.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.report.calculator.calculateReportPurchasePrice
import com.dandi.nyummy.report.calculator.isPurchasableWeek
import com.dandi.nyummy.report.config.ReportProperties
import com.dandi.nyummy.report.dto.CreateReportPurchaseRequest
import com.dandi.nyummy.report.dto.ReportPurchaseResponse
import com.dandi.nyummy.report.entity.Report
import com.dandi.nyummy.report.entity.ReportGenerationJob
import com.dandi.nyummy.report.entity.ReportPurchase
import com.dandi.nyummy.report.enum.ReportPurchaseOutcome
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.report.repository.ReportGenerationJobRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRepository
import com.dandi.nyummy.report.repository.ReportRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * 리포트 구매 트랜잭션. 실패하면 예외로 롤백되고, 실패 기록은 [ReportPurchaseService]가 롤백 뒤에 따로 남긴다.
 * 트랜잭션 안에서 예외를 잡으면 롤백이 표시된 채로 기록을 남기게 되므로 별도 빈으로 둔다.
 */
@Service
class ReportPurchaseTransactionService(
    private val clock: Clock,
    private val reportProperties: ReportProperties,
    private val coinService: CoinService,
    private val reportPurchaseAttemptService: ReportPurchaseAttemptService,
    private val reportRepository: ReportRepository,
    private val reportPurchaseRepository: ReportPurchaseRepository,
    private val reportGenerationJobRepository: ReportGenerationJobRepository,
    private val mealRepository: MealRepository,
) {
    companion object {
        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }

    /**
     * 한 주의 리포트를 사고, Worker가 가져갈 생성 작업을 함께 만든다. 리포트는 WAITING으로 생긴다.
     *
     * 코인 행 잠금이 첫 문장이다. 같은 사용자의 구매·취소·환불이 하나씩 처리되고, REPEATABLE READ의 스냅샷이 잠금 뒤에 생겨
     * 이후 조회가 앞 요청이 커밋한 데이터를 본다. 그래서 같은 키로 동시에 와도 뒤 요청은 앞 구매를 찾아 처음 결과를 돌려받고,
     * 같은 주를 동시에 사도 뒤 요청은 앞 리포트를 보고 거부된다. Worker의 실패 환불·사용자 취소도 코인 행을 먼저 잠그므로
     * 첫 무료 판정과 겹치지 않는다.
     *
     * @throws BusinessException [ReportErrorCode.IDEMPOTENCY_KEY_REUSED] 같은 키로 다른 주·가격을 보낸 경우
     * @throws BusinessException [ReportErrorCode.INVALID_WEEK] startDate가 일요일이 아니거나 아직 끝나지 않은 주인 경우
     * @throws BusinessException [ReportErrorCode.DUPLICATE_WEEK] 취소·실패하지 않은 같은 주의 리포트가 있는 경우
     * @throws BusinessException [ReportErrorCode.NOT_ENOUGH_MEALS] 그 주에 분석이 완료된 식사가 최소 개수보다 적은 경우
     * @throws BusinessException [ReportErrorCode.PRICE_CHANGED] 화면에서 본 가격과 지금 가격이 다른 경우
     * @throws BusinessException [ReportErrorCode.INSUFFICIENT_BALANCE] 가격이 잔액보다 많은 경우
     */
    @Transactional
    fun createReportPurchase(
        userId: Long,
        idempotencyKey: String,
        request: CreateReportPurchaseRequest,
        appVersion: String?,
        language: Language,
    ): ReportPurchaseResponse {
        val coin = coinService.lockCoin(userId)

        reportPurchaseRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)?.let { reportPurchase ->
            return replayReportPurchase(reportPurchase, request, appVersion, coin)
        }

        val startDate = request.startDate
        if (!isPurchasableWeek(startDate, LocalDate.ofInstant(clock.instant(), ZONE))) {
            throw BusinessException(ReportErrorCode.INVALID_WEEK)
        }
        if (reportRepository.existsByUserIdAndStartDateAndStatusIn(userId, startDate, ReportStatus.ACTIVE_STATUSES)) {
            throw BusinessException(ReportErrorCode.DUPLICATE_WEEK)
        }
        val completedMealCount = mealRepository.countMealsByUserIdAndStatusAndPeriod(
            userId = userId,
            status = MealStatus.COMPLETED,
            start = startDate.atStartOfDay(ZONE).toInstant(),
            end = startDate.plusWeeks(1).atStartOfDay(ZONE).toInstant(),
        )
        if (completedMealCount < reportProperties.minMealCount) {
            throw BusinessException(ReportErrorCode.NOT_ENOUGH_MEALS)
        }

        val purchasePrice = calculateReportPurchasePrice(
            hasFreeReportPurchase = reportPurchaseRepository.existsFreeReportPurchase(
                userId,
                ReportStatus.ACTIVE_STATUSES,
            ),
            listPrice = reportProperties.listPrice,
        )
        if (purchasePrice != request.purchasePrice) throw BusinessException(ReportErrorCode.PRICE_CHANGED)
        if (purchasePrice > coin.balance) throw BusinessException(ReportErrorCode.INSUFFICIENT_BALANCE)

        val report = reportRepository.save(Report(userId, startDate))
        val reportPurchase = reportPurchaseRepository.save(
            ReportPurchase(
                userId = userId,
                reportId = report.id,
                purchasePrice = purchasePrice,
                idempotencyKey = idempotencyKey,
            ),
        )
        reportGenerationJobRepository.save(ReportGenerationJob(report.id, language))

        // 0코인 구매(첫 무료)는 잔액 변동이 없어 내역을 남기지 않는다
        if (purchasePrice > 0) {
            coinService.decreaseBalance(coin, purchasePrice, CoinHistoryCategory.REPORT_PURCHASE, reportPurchase.id)
        }
        reportPurchaseAttemptService.createReportPurchaseAttempt(
            userId = userId,
            idempotencyKey = idempotencyKey,
            request = request,
            appVersion = appVersion,
            outcome = ReportPurchaseOutcome.SUCCEEDED,
            reportPurchaseId = reportPurchase.id,
        )

        return ReportPurchaseResponse(
            reportId = report.id,
            status = ReportStatus.WAITING,
            purchasePrice = purchasePrice,
            balance = coin.balance,
        )
    }

    // 같은 키의 구매가 있으면 요청이 같은지 보고 처음 응답을 다시 만든다. 상태는 처음 응답 그대로(WAITING)이고, 잔액은 지금 값이다.
    private fun replayReportPurchase(
        reportPurchase: ReportPurchase,
        request: CreateReportPurchaseRequest,
        appVersion: String?,
        coin: Coin,
    ): ReportPurchaseResponse {
        val report = reportRepository.findByIdOrNull(reportPurchase.reportId)
            ?: throw IllegalStateException("구매의 리포트가 없습니다: reportPurchaseId=${reportPurchase.id}")
        if (report.startDate != request.startDate || reportPurchase.purchasePrice != request.purchasePrice) {
            throw BusinessException(ReportErrorCode.IDEMPOTENCY_KEY_REUSED)
        }

        reportPurchaseAttemptService.createReportPurchaseAttempt(
            userId = reportPurchase.userId,
            idempotencyKey = reportPurchase.idempotencyKey,
            request = request,
            appVersion = appVersion,
            outcome = ReportPurchaseOutcome.REPLAYED,
            reportPurchaseId = reportPurchase.id,
        )

        return ReportPurchaseResponse(
            reportId = report.id,
            status = ReportStatus.WAITING,
            purchasePrice = reportPurchase.purchasePrice,
            balance = coin.balance,
        )
    }
}

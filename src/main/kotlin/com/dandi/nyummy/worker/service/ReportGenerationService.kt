package com.dandi.nyummy.worker.service

import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.infra.ai.report.ReportGenerationClient
import com.dandi.nyummy.infra.ai.report.ReportGenerationFailureCategory
import com.dandi.nyummy.infra.ai.report.ReportGenerationFailureException
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.meal.calculator.calculateRecommendedDailyIntake
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import com.dandi.nyummy.report.calculator.NUTRITION_BALANCE_VIEW_TYPE
import com.dandi.nyummy.report.calculator.calculateNutritionBalance
import com.dandi.nyummy.report.dto.NutritionBalanceContentResponse
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.worker.config.ConditionalOnReportWorkerEnabled
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.ReportGenerationJobResult
import com.dandi.nyummy.worker.dto.ReportPushResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
@ConditionalOnReportWorkerEnabled
class ReportGenerationService(
    private val reportGenerationClient: ReportGenerationClient,
    private val reportGenerationJobTransactionService: ReportGenerationJobTransactionService,
    private val mealRepository: MealRepository,
    private val profileRepository: ProfileRepository,
    private val notificationService: NotificationService,
    private val appLinkProperties: AppLinkProperties,
    private val workerProperties: WorkerProperties,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ReportGenerationService::class.java)
        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val PUSH_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

        // 설정 오류로 되돌린 작업을 다시 가져가기까지 기다리는 시간. 설정이 고장 난 동안 Gemini를 계속 부르지 않게 한다.
        private val MISCONFIGURED_DELAY = Duration.ofMinutes(1)

        // 생성 시도 한 번의 결과와 걸린 시간(식사 집계 + Gemini + 결과 저장).
        private const val GENERATION_METER = "nyummy.report.generation"

        // 작업이 쌓인 때(구매)부터 COMPLETED·FAILED로 끝날 때까지 걸린 시간. 사용자가 리포트를 기다린 시간이다.
        private const val GENERATION_ELAPSED_METER = "nyummy.report.generation.elapsed"

        // 분류된 실패가 아닌 예외의 category 태그 값
        private const val UNKNOWN_CATEGORY = "unknown"
    }

    /**
     * Worker가 lease로 가져간 작업 한 건으로 리포트를 만들고 결과를 알린다. 실행기 스레드에서 돈다.
     *
     * 그 주에 분석이 완료된 식사(지운 식사 제외)를 서버가 집계해 숫자와 숫자 문장을 만들고, Gemini는 제목·조언·제안·마무리만 쓴다.
     * 목표 섭취량은 생성 시점 프로필로 정한다. Gemini 호출은 트랜잭션 밖에서 하고, 결과는 짧은 트랜잭션으로 확정한다.
     * 실패 처리는 [handleReportGenerationFailure]를 본다.
     *
     * lease가 만료돼 다른 Worker가 가져갔거나 사용자가 취소했으면 결과를 쓰지 않고 알리지도 않는다.
     */
    fun generateReport(job: ReportGenerationJobResult) {
        val generatedAt = clock.instant()

        try {
            val start = job.startDate.atStartOfDay(ZONE).toInstant()
            val end = job.startDate.plusWeeks(1).atStartOfDay(ZONE).toInstant()
            val meals = mealRepository.findMealsByUserIdAndPeriod(job.userId, start, end)
                .filter { it.status == MealStatus.COMPLETED }
            val target = calculateRecommendedDailyIntake(
                profileRepository.findProfileByUserId(job.userId),
                LocalDate.ofInstant(generatedAt, ZONE),
            )
            val nutritionBalance = calculateNutritionBalance(meals, job.startDate, target, ZONE)
            val text = reportGenerationClient.generateReportText(nutritionBalance)
            val content = NutritionBalanceContentResponse(
                mealCount = nutritionBalance.mealCount,
                recordedDayCount = nutritionBalance.recordedDayCount,
                current = nutritionBalance.current,
                target = nutritionBalance.target,
                macroNutrients = nutritionBalance.macroNutrients,
                days = nutritionBalance.days,
                calorySentence = nutritionBalance.calorySentence,
                focusSentence = nutritionBalance.focusSentence,
                overTargetSentence = nutritionBalance.overTargetSentence,
                breakfastSentence = nutritionBalance.breakfastSentence,
                advice = text.advice,
                suggestions = text.suggestions,
                closing = text.closing,
            )

            val pushResult = reportGenerationJobTransactionService.confirmReportGeneration(
                job = job,
                title = text.title,
                viewType = NUTRITION_BALANCE_VIEW_TYPE,
                content = objectMapper.writeValueAsString(content),
            )
            if (pushResult != null) {
                recordReportGeneration(job, ReportGenerationOutcome.COMPLETED, "none", generatedAt)
            }
            sendReportPush(pushResult)
        } catch (e: ReportGenerationFailureException) {
            handleReportGenerationFailure(job, e, generatedAt)
        } catch (e: RuntimeException) {
            logger.error("리포트 생성 중 예상하지 못한 오류로 실패합니다: reportId={}, leaseCount={}", job.reportId, job.leaseCount, e)
            failReportGeneration(job, UNKNOWN_CATEGORY, generatedAt)
        }
    }

    /**
     * 분류된 실패를 재시도할지 FAILED로 끝낼지 정한다.
     *
     * - 일시적인 실패(UNAVAILABLE)는 재시도할 수 있으면 [com.dandi.nyummy.worker.config.WorkerJobProperties.retryDelay] 뒤로 미뤄
     *   되돌린다. Gemini 장애 중에 바로 다시 불러 시도를 다 쓰지 않게 한다.
     * - 규칙을 어긴 응답(INVALID_RESPONSE)은 다시 만들면 맞을 수 있어 재시도할 수 있으면 바로 되돌린다.
     * - 설정 오류(MISCONFIGURED)는 사용자 탓이 아니고 모든 작업이 같이 실패하므로 시도 횟수를 쓰지 않고 잠시 뒤로 미룬다.
     * - 그 밖(재시도를 다 쓴 실패, 거부된 요청)은 FAILED로 끝내고 유료였으면 환불한다.
     *
     * Worker가 종료될 때 대기 시간 안에 끝나지 않은 작업도 같은 규칙으로 정리한다.
     */
    fun handleReportGenerationFailure(job: ReportGenerationJobResult, failure: ReportGenerationFailureException) {
        handleReportGenerationFailure(job, failure, clock.instant())
    }

    /**
     * 최대 시도 수를 채워 lease로 가져가지 않은 작업을 FAILED로 끝낸다. 유료였으면 환불하고 알린다.
     */
    fun failExhaustedReportGeneration(job: ReportGenerationJobResult) {
        failReportGeneration(job, "exhausted", clock.instant())
    }

    // 기본 인자로 시각을 받지 않는다. Kotlin은 기본 인자 호출을 정적 보조 메서드로 바꿔 mock으로 가로챌 수 없게 된다.
    private fun handleReportGenerationFailure(
        job: ReportGenerationJobResult,
        failure: ReportGenerationFailureException,
        generatedAt: Instant,
    ) {
        val category = failure.category.name.lowercase()
        when (failure.category) {
            ReportGenerationFailureCategory.UNAVAILABLE, ReportGenerationFailureCategory.INVALID_RESPONSE -> {
                if (job.canRetry) {
                    val delay = if (failure.category == ReportGenerationFailureCategory.UNAVAILABLE) {
                        workerProperties.report.retryDelay
                    } else {
                        Duration.ZERO
                    }
                    logger.warn(
                        "리포트 생성이 실패해 {}초 뒤 다시 시도합니다: reportId={}, leaseCount={}, {}",
                        delay.toSeconds(),
                        job.reportId,
                        job.leaseCount,
                        failure.message,
                    )
                    if (reportGenerationJobTransactionService.retryReportGeneration(job, delay)) {
                        recordReportGeneration(job, ReportGenerationOutcome.RETRIED, category, generatedAt)
                    }
                    return
                }
                logger.error(
                    "리포트 생성이 재시도 후에도 실패했습니다: reportId={}, leaseCount={}",
                    job.reportId,
                    job.leaseCount,
                    failure,
                )
            }

            ReportGenerationFailureCategory.INVALID_REQUEST ->
                logger.error("리포트 생성 요청이 거부됐습니다: reportId={}", job.reportId, failure)

            ReportGenerationFailureCategory.MISCONFIGURED -> {
                logger.error("설정 오류로 리포트를 만들지 못해 잠시 뒤 다시 시도합니다: reportId={}", job.reportId, failure)
                if (reportGenerationJobTransactionService.cancelReportGeneration(job, MISCONFIGURED_DELAY)) {
                    recordReportGeneration(job, ReportGenerationOutcome.DELAYED, category, generatedAt)
                }
                return
            }
        }
        failReportGeneration(job, category, generatedAt)
    }

    private fun failReportGeneration(job: ReportGenerationJobResult, category: String, generatedAt: Instant) {
        val pushResult = reportGenerationJobTransactionService.failReportGeneration(job)
        if (pushResult != null) {
            recordReportGeneration(job, ReportGenerationOutcome.FAILED, category, generatedAt)
        }
        sendReportPush(pushResult)
    }

    /**
     * 이 Worker가 결과를 실제로 남긴 시도만 기록한다. COMPLETED·FAILED로 끝났으면 구매부터 걸린 시간도 기록한다.
     */
    private fun recordReportGeneration(
        job: ReportGenerationJobResult,
        outcome: ReportGenerationOutcome,
        category: String,
        generatedAt: Instant,
    ) {
        val now = clock.instant()
        Timer.builder(GENERATION_METER)
            .tag("outcome", outcome.tagValue)
            .tag("category", category)
            .register(meterRegistry)
            .record(Duration.between(generatedAt, now))

        if (outcome.isFinal) {
            Timer.builder(GENERATION_ELAPSED_METER)
                .tag("outcome", outcome.tagValue)
                .register(meterRegistry)
                .record(Duration.between(job.createdAt, now))
        }
    }

    /**
     * 리포트가 끝났음을 푸시로 알린다. 상태가 이미 커밋된 뒤에 보내므로 발송이 실패해도 결과는 남는다.
     * 완료는 리포트 상세로, 실패는 "다시 받기"를 누를 수 있는 리포트 목록으로 보낸다.
     */
    private fun sendReportPush(pushResult: ReportPushResult?) {
        // 이 Worker의 작업이 아니었으면 결과를 쓰지 않았으므로 알리지도 않는다.
        if (pushResult == null) {
            return
        }

        try {
            val result = notificationService.sendServicePush(pushResult.userId, convertPushMessage(pushResult))
            notificationService.deleteInvalidDeviceTokens(result.invalidTokens)
        } catch (e: RuntimeException) {
            logger.error("리포트 알림 발송 실패: reportId={}, status={}", pushResult.reportId, pushResult.status, e)
        }
    }

    private fun convertPushMessage(pushResult: ReportPushResult): PushMessage {
        val notificationId = NotificationId.createReportId(pushResult.reportId, pushResult.status)
        val week = "${pushResult.startDate.format(PUSH_DATE_FORMATTER)}~" +
            pushResult.startDate.plusDays(6).format(PUSH_DATE_FORMATTER)

        return when {
            pushResult.status == ReportStatus.COMPLETED -> PushMessage(
                userId = pushResult.userId,
                type = PushType.REPORT_COMPLETED,
                notificationId = notificationId,
                title = "주간 영양 리포트가 도착했다냥 🐾",
                body = "$week 주간 영양 리포트가 도착했어요",
                appLink = appLinkProperties.createReportDetailLink(pushResult.reportId),
            )

            pushResult.refundedCoinQuantity > 0 -> PushMessage(
                userId = pushResult.userId,
                type = PushType.REPORT_FAILED,
                notificationId = notificationId,
                title = "리포트를 만들지 못했다냥 🐾",
                body = "$week 리포트를 만들지 못해 ${pushResult.refundedCoinQuantity}코인을 돌려드렸어요",
                appLink = appLinkProperties.reports,
            )

            else -> PushMessage(
                userId = pushResult.userId,
                type = PushType.REPORT_FAILED,
                notificationId = notificationId,
                title = "리포트를 만들지 못했다냥 🐾",
                body = "$week 리포트를 만들지 못했어요. 다시 무료로 만들 수 있어요",
                appLink = appLinkProperties.reports,
            )
        }
    }
}

private enum class ReportGenerationOutcome(val tagValue: String, val isFinal: Boolean) {
    COMPLETED("completed", true),
    RETRIED("retried", false),
    DELAYED("delayed", false),
    FAILED("failed", true),
}

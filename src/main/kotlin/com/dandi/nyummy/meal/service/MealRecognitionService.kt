package com.dandi.nyummy.meal.service

import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.ai.recognition.MealRecognitionClient
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.calculator.calculateRecognitionTimeout
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.MealRecognitionOutcome
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.enum.MealRecognitionDeferralCategory
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 식사 기록 요청 안에서 음식 인식을 시도한다. 응답 기한을 지키는 것이 이 서비스의 일이다.
 */
@Service
class MealRecognitionService(
    private val mealRecognitionClient: MealRecognitionClient,
    private val mealRecognitionExecutor: ThreadPoolTaskExecutor,
    private val mealProperties: MealProperties,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {

    companion object {
        private val logger = LoggerFactory.getLogger(MealRecognitionService::class.java)

        // 식사 기록 요청 안의 인식 시도 한 번의 결과와 걸린 시간.
        // outcome은 recognized·not_food·deferred이고, category는 넘긴 이유(넘기지 않았으면 none)다.
        private const val RECOGNITION_METER = "nyummy.meal.recognition"
    }

    /**
     * 사진의 음식을 응답 기한 안에서 인식한다. 예외를 던지지 않고, 어떻게 끝났는지를 [MealRecognitionOutcome]으로 돌려준다.
     *
     * 인식은 전용 실행기에서 돌리고 요청 스레드는 남은 시간만 기다린다. 넘으면 결과를 버리고 넘긴다.
     * 포기한 호출은 응답 대기 시간도 같은 값으로 줄여 두었으므로 그즈음 스스로 끝나 실행기 스레드를 놓는다.
     *
     * 음식이 아니라는 판정만 그대로 돌려주고, 그 밖에는 요청 안에서 끝내지 못한 것으로 보고 Worker의 인식 작업으로 넘긴다.
     * 인식의 실패도 넘긴다. Worker에는 실패를 다루는 정책(재시도, 설정 오류면 미룸, 끝내 실패하면 알림)이 이미 있다.
     *
     * @param receivedAt 서버가 식사 기록 요청을 받은 시각. 응답 기한을 여기서부터 센다
     */
    fun recognizeMeal(
        imageBytes: ByteArray,
        contentType: String,
        language: Language,
        receivedAt: Instant,
    ): MealRecognitionOutcome {
        val startedAt = Instant.now(clock)
        val outcome = recognizeMealWithinTimeout(imageBytes, contentType, language, receivedAt, startedAt)

        recordRecognition(outcome, Duration.between(startedAt, Instant.now(clock)))

        return outcome
    }

    private fun recognizeMealWithinTimeout(
        imageBytes: ByteArray,
        contentType: String,
        language: Language,
        receivedAt: Instant,
        startedAt: Instant,
    ): MealRecognitionOutcome {
        val timeout = calculateRecognitionTimeout(receivedAt, startedAt, mealProperties.recognition)
            ?: return MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.INSUFFICIENT_TIME)

        val future = try {
            mealRecognitionExecutor.submit(
                Callable { mealRecognitionClient.recognizeMeal(imageBytes, contentType, language, timeout) },
            )
        } catch (e: TaskRejectedException) {
            return MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.SATURATED)
        }

        return try {
            val recognition: MealRecognitionResult = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            MealRecognitionOutcome.Recognized(recognition)
        } catch (e: TimeoutException) {
            future.cancel(true)
            MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.TIMEOUT)
        } catch (e: ExecutionException) {
            convertFailure(e.cause)
        } catch (e: InterruptedException) {
            // 요청 스레드가 중단됐다(서버 종료 등). 중단 표시를 되살리고, 식사는 Worker가 이어서 인식하게 넘긴다.
            future.cancel(true)
            Thread.currentThread().interrupt()
            MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.FAILED)
        }
    }

    private fun convertFailure(cause: Throwable?): MealRecognitionOutcome {
        if (cause is AnalysisFailureException && cause.category == AnalysisFailureCategory.NOT_FOOD) {
            return MealRecognitionOutcome.NotFood
        }
        if (cause is AnalysisFailureException) {
            logger.warn("식사 기록 요청 안의 음식 인식이 실패해 Worker로 넘깁니다: category={}", cause.category, cause)
        } else {
            logger.error("식사 기록 요청 안의 음식 인식이 분류되지 않은 예외로 실패해 Worker로 넘깁니다.", cause)
        }

        return MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.FAILED)
    }

    private fun recordRecognition(outcome: MealRecognitionOutcome, elapsed: Duration) {
        val (outcomeTag, categoryTag) = when (outcome) {
            is MealRecognitionOutcome.Recognized -> "recognized" to "none"
            MealRecognitionOutcome.NotFood -> "not_food" to "none"
            is MealRecognitionOutcome.Deferred -> "deferred" to outcome.category.name.lowercase()
        }

        Timer.builder(RECOGNITION_METER)
            .tag("outcome", outcomeTag)
            .tag("category", categoryTag)
            .register(meterRegistry)
            .record(elapsed)
    }
}

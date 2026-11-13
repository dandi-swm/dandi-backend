package com.dandi.nyummy.meal.service

import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.ai.recognition.MealRecognitionClient
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.config.MealRecognitionProperties
import com.dandi.nyummy.meal.config.RewardBoxProperties
import com.dandi.nyummy.meal.config.TutorialRewardBoxProperties
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionOutcome
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionDeferralCategory
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 식사 기록 요청 안의 음식 인식이 응답 기한을 지키는지, 끝내지 못한 경우를 이유별로 Worker에 넘기는지 다룬다.
 * 실행기와 Future는 mockk로 흉내 내 기한 초과와 포화를 시간을 쓰지 않고 만든다.
 */
class MealRecognitionServiceTest {

    private val now = Instant.parse("2026-11-14T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val imageBytes = byteArrayOf(1, 2, 3)
    private val contentType = "image/jpeg"
    private val recognition = MealRecognitionResult(
        inputCategory = MealInputCategory.FOOD_PHOTO,
        name = "불고기 백반",
        foods = listOf(MealFoodRecognitionResult(name = "불고기", portion = BigDecimal("1.5"))),
        model = "gemini-recognition-test",
        promptVersion = "v1",
    )

    // 기한 4.5초에서 저장 몫 400ms를 빼면, 앞 단계가 쓴 시간이 없을 때 인식은 4.1초를 받는다.
    private val mealProperties = MealProperties(
        maxDailyCount = 5,
        analysisFeedbackRate = 100,
        intakeTolerance = 0.2,
        minPositiveMacroCount = 2,
        rewardBoxes = mapOf(
            RewardBoxCategory.BRONZE to RewardBoxProperties(rate = 40, minCoinQuantity = 100, maxCoinQuantity = 199),
            RewardBoxCategory.SILVER to RewardBoxProperties(rate = 40, minCoinQuantity = 200, maxCoinQuantity = 349),
            RewardBoxCategory.GOLD to RewardBoxProperties(rate = 20, minCoinQuantity = 350, maxCoinQuantity = 500),
        ),
        rewardMailTimeToLive = Duration.ofDays(7),
        tutorialRewardBox = TutorialRewardBoxProperties(itemIds = listOf(2L, 3L), coinQuantity = 100),
        recognition = MealRecognitionProperties(
            responseTimeToLive = Duration.ofMillis(4500),
            reservedSaveTime = Duration.ofMillis(400),
            minRecognitionTime = Duration.ofSeconds(2),
            maxThreads = 30,
            nutritionDelay = Duration.ofMinutes(5),
        ),
    )

    private val mealRecognitionClient = mockk<MealRecognitionClient>()
    private val mealRecognitionExecutor = mockk<ThreadPoolTaskExecutor>()
    private val future = mockk<Future<MealRecognitionResult>>()
    private val meterRegistry = SimpleMeterRegistry()

    private val mealRecognitionService = MealRecognitionService(
        mealRecognitionClient = mealRecognitionClient,
        mealRecognitionExecutor = mealRecognitionExecutor,
        mealProperties = mealProperties,
        meterRegistry = meterRegistry,
        clock = clock,
    )

    private val submittedTask = slot<Callable<MealRecognitionResult>>()

    init {
        every { mealRecognitionExecutor.submit(capture(submittedTask)) } returns future
        every { future.get(any(), any()) } returns recognition
        every { future.cancel(true) } returns true
    }

    private fun recognizeMeal(elapsedMillis: Long = 0) =
        mealRecognitionService.recognizeMeal(imageBytes, contentType, Language.KO, now.minusMillis(elapsedMillis))

    private fun givenRecognitionFailure(cause: Throwable) {
        every { future.get(any(), any()) } throws ExecutionException(cause)
    }

    private fun countRecognitions(outcome: String, category: String): Long = meterRegistry
        .get("nyummy.meal.recognition")
        .tags("outcome", outcome, "category", category)
        .timer()
        .count()

    @Test
    fun `응답 기한 안에 인식하면 인식 결과를 돌려준다`() {
        assertEquals(MealRecognitionOutcome.Recognized(recognition), recognizeMeal())
    }

    @Test
    fun `인식에는 남은 시간을 주고 요청 스레드도 그 시간만 기다린다`() {
        every { mealRecognitionClient.recognizeMeal(any(), any(), any(), any()) } returns recognition

        recognizeMeal(elapsedMillis = 800)
        submittedTask.captured.call()

        // 4500 - 800 - 400
        verify { mealRecognitionClient.recognizeMeal(imageBytes, contentType, Language.KO, Duration.ofMillis(3300)) }
        verify { future.get(3300, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `음식이 아니면 넘기지 않고 음식 아님으로 돌려준다`() {
        givenRecognitionFailure(AnalysisFailureException(AnalysisFailureCategory.NOT_FOOD, "음식이 아닙니다."))

        assertEquals(MealRecognitionOutcome.NotFood, recognizeMeal())
    }

    @Test
    fun `남은 시간이 인식 최소 시간보다 적으면 인식을 부르지 않고 넘긴다`() {
        val outcome = recognizeMeal(elapsedMillis = 3000)

        assertEquals(MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.INSUFFICIENT_TIME), outcome)
        verify(exactly = 0) { mealRecognitionExecutor.submit(any<Callable<MealRecognitionResult>>()) }
    }

    @Test
    fun `인식 실행기가 다 찼으면 기다리지 않고 넘긴다`() {
        every { mealRecognitionExecutor.submit(any<Callable<MealRecognitionResult>>()) } throws
            TaskRejectedException("실행기가 다 찼습니다.")

        assertEquals(MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.SATURATED), recognizeMeal())
    }

    @Test
    fun `응답 기한까지 인식이 끝나지 않으면 인식을 취소하고 넘긴다`() {
        every { future.get(any(), any()) } throws TimeoutException()

        assertEquals(MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.TIMEOUT), recognizeMeal())
        verify(exactly = 1) { future.cancel(true) }
    }

    @Test
    fun `인식이 실패하면 다시 시도할 수 있는 실패든 아니든 넘긴다`() {
        val categories = AnalysisFailureCategory.entries - AnalysisFailureCategory.NOT_FOOD

        categories.forEach { category ->
            givenRecognitionFailure(AnalysisFailureException(category, "실패"))

            assertEquals(
                MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.FAILED),
                recognizeMeal(),
                "실패 분류 $category",
            )
        }
    }

    @Test
    fun `인식이 분류되지 않은 예외로 실패해도 넘긴다`() {
        givenRecognitionFailure(IllegalStateException("예상 밖"))

        assertEquals(MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.FAILED), recognizeMeal())
    }

    @Test
    fun `시도한 결과와 넘긴 이유를 지표에 남긴다`() {
        recognizeMeal()
        every { future.get(any(), any()) } throws TimeoutException()
        recognizeMeal()
        givenRecognitionFailure(AnalysisFailureException(AnalysisFailureCategory.NOT_FOOD, "음식이 아닙니다."))
        recognizeMeal()

        assertEquals(1, countRecognitions(outcome = "recognized", category = "none"))
        assertEquals(1, countRecognitions(outcome = "deferred", category = "timeout"))
        assertEquals(1, countRecognitions(outcome = "not_food", category = "none"))
    }
}

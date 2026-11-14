package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.config.MealRecognitionProperties
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MealRecognitionTimeoutCalculatorTest {

    // 기한 4.5초에서 저장 몫 400ms를 빼면, 앞 단계가 쓴 시간이 0일 때 인식은 4.1초를 받는다.
    private val recognitionProperties = MealRecognitionProperties(
        responseTimeToLive = Duration.ofMillis(4500),
        reservedSaveTime = Duration.ofMillis(400),
        minRecognitionTime = Duration.ofSeconds(2),
        maxThreads = 30,
        nutritionDelay = Duration.ofMinutes(5),
    )
    private val receivedAt = Instant.parse("2026-11-14T03:00:00Z")

    private fun calculateRecognitionTimeout(elapsedMillis: Long) =
        calculateRecognitionTimeout(receivedAt, receivedAt.plusMillis(elapsedMillis), recognitionProperties)

    @Test
    fun `인식은 기한에서 지금까지 쓴 시간과 저장 몫을 뺀 만큼 받는다`() {
        assertEquals(Duration.ofMillis(4100), calculateRecognitionTimeout(elapsedMillis = 0))
        assertEquals(Duration.ofMillis(3300), calculateRecognitionTimeout(elapsedMillis = 800))
    }

    @Test
    fun `남은 시간이 인식 최소 시간과 같으면 인식을 부른다`() {
        assertEquals(Duration.ofSeconds(2), calculateRecognitionTimeout(elapsedMillis = 2100))
    }

    @Test
    fun `남은 시간이 인식 최소 시간보다 적으면 인식을 부르지 않는다`() {
        assertNull(calculateRecognitionTimeout(elapsedMillis = 2101))
    }

    @Test
    fun `앞 단계에서 이미 기한을 넘겼으면 인식을 부르지 않는다`() {
        assertNull(calculateRecognitionTimeout(elapsedMillis = 5000))
    }
}

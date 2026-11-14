package com.dandi.nyummy.meal.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MealRecognitionPropertiesTest {

    private fun createRecognitionProperties(
        responseTimeToLive: Duration = Duration.ofMillis(4500),
        maxThreads: Int = 30,
        nutritionDelay: Duration = Duration.ofMinutes(5),
    ) = MealRecognitionProperties(
        responseTimeToLive = responseTimeToLive,
        reservedSaveTime = Duration.ofMillis(400),
        minRecognitionTime = Duration.ofSeconds(2),
        maxThreads = maxThreads,
        nutritionDelay = nutritionDelay,
    )

    @Test
    fun `기한이 저장 몫과 인식 최소 시간을 더한 값 이상이면 만들어진다`() {
        createRecognitionProperties(responseTimeToLive = Duration.ofMillis(2400))
    }

    @Test
    fun `기한이 저장 몫과 인식 최소 시간을 더한 값보다 짧으면 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            createRecognitionProperties(responseTimeToLive = Duration.ofMillis(2399))
        }
    }

    @Test
    fun `동시 인식 수가 1보다 작으면 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> { createRecognitionProperties(maxThreads = 0) }
    }

    @Test
    fun `영양 작업 지연이 음수면 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            createRecognitionProperties(nutritionDelay = Duration.ofSeconds(-1))
        }
    }
}

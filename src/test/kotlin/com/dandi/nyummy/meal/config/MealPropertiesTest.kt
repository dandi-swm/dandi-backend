package com.dandi.nyummy.meal.config

import com.dandi.nyummy.mail.enum.RewardBoxCategory
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MealPropertiesTest {

    private val rewardBoxes = mapOf(
        RewardBoxCategory.BRONZE to RewardBoxProperties(rate = 40, minCoinQuantity = 100, maxCoinQuantity = 199),
        RewardBoxCategory.SILVER to RewardBoxProperties(rate = 40, minCoinQuantity = 200, maxCoinQuantity = 349),
        RewardBoxCategory.GOLD to RewardBoxProperties(rate = 20, minCoinQuantity = 350, maxCoinQuantity = 500),
    )

    private fun createMealProperties(rewardBoxes: Map<RewardBoxCategory, RewardBoxProperties>) = MealProperties(
        maxDailyCount = 5,
        analysisFeedbackRate = 100,
        intakeTolerance = 0.2,
        minPositiveMacroCount = 2,
        rewardBoxes = rewardBoxes,
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

    // rewardBoxes

    @Test
    fun `모든 상자 등급이 있고 rate 합이 100이면 만들어진다`() {
        createMealProperties(rewardBoxes)
    }

    @Test
    fun `상자 등급 설정이 하나라도 빠지면 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            createMealProperties(rewardBoxes - RewardBoxCategory.GOLD)
        }
    }

    @Test
    fun `rate 합이 100이 아니면 만들 수 없다`() {
        val gold =
            RewardBoxCategory.GOLD to RewardBoxProperties(rate = 30, minCoinQuantity = 350, maxCoinQuantity = 500)

        assertFailsWith<IllegalArgumentException> {
            createMealProperties(rewardBoxes + gold)
        }
    }

    // RewardBoxProperties

    @Test
    fun `코인 최솟값이 최댓값보다 크면 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            RewardBoxProperties(rate = 40, minCoinQuantity = 200, maxCoinQuantity = 100)
        }
    }

    @Test
    fun `rate가 0보다 작거나 100보다 크면 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            RewardBoxProperties(rate = -1, minCoinQuantity = 100, maxCoinQuantity = 199)
        }
        assertFailsWith<IllegalArgumentException> {
            RewardBoxProperties(rate = 101, minCoinQuantity = 100, maxCoinQuantity = 199)
        }
    }
}

package com.dandi.nyummy.meal.config

import com.dandi.nyummy.mail.enum.RewardBoxCategory
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.meal")
data class MealProperties(
    val maxDailyCount: Int,
    val analysisFeedbackRate: Int,
    val intakeTolerance: Double,
    val minPositiveMacroCount: Int,
    val rewardBoxes: Map<RewardBoxCategory, RewardBoxProperties>,
    val rewardMailTimeToLive: Duration,
    val tutorialRewardBox: TutorialRewardBoxProperties,
    val recognition: MealRecognitionProperties,
) {
    // 설정이 틀린 채로 상자를 나눠 주지 않도록 앱이 뜰 때 막는다.
    init {
        require(rewardBoxes.keys == RewardBoxCategory.entries.toSet()) {
            "app.meal.reward-boxes에 모든 상자 등급의 설정이 있어야 합니다: ${rewardBoxes.keys}"
        }
        require(rewardBoxes.values.sumOf { it.rate } == 100) {
            "app.meal.reward-boxes의 rate 합은 100이어야 합니다: ${rewardBoxes.values.sumOf { it.rate }}"
        }
    }
}

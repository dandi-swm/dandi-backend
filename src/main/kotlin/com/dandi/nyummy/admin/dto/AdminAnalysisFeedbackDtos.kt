@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.admin.dto

import com.dandi.nyummy.meal.dto.Nutrition
import java.time.Instant

data class AdminAnalysisFeedbacksResponse(
    val analysisFeedbacks: List<AdminAnalysisFeedbackResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

/**
 * 분석 품질을 보는 화면이라 누가 평가했는지는 필요 없어 userId를 내리지 않는다(최소 열람).
 */
data class AdminAnalysisFeedbackResponse(
    val analysisFeedbackId: Long,
    val rating: Float,
    val reason: String?,
    val createdAt: Instant,
    val meal: AdminAnalysisFeedbackMealResponse,
)

/**
 * 평가 대상 식사. 메뉴명·영양은 사용자가 평가 뒤에 고쳤을 수 있어 분석 당시 값이 아니라 지금 값이다.
 * 사용자가 지운 식사도 평가는 남으므로 [deletedAt]으로 구분한다.
 */
data class AdminAnalysisFeedbackMealResponse(
    val mealId: Long,
    val name: String,
    val mealAt: Instant,
    val nutrition: Nutrition,
    val catComment: String?,
    val imageUrl: String,
    val deletedAt: Instant?,
)

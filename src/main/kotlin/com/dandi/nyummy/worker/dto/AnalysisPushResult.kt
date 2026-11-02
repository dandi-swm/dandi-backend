package com.dandi.nyummy.worker.dto

import com.dandi.nyummy.meal.enum.MealStatus

/**
 * 분석을 COMPLETED·FAILED로 끝낸 뒤 결과 알림에 쓰는 값. 트랜잭션이 끝난 뒤 알림을 보내므로 엔티티가 아닌 값만 담는다.
 *
 * @property isDeleted 분석하는 동안 사용자가 식사를 지웠는지. 지웠으면 알림을 보내지 않는다
 */
data class AnalysisPushResult(
    val mealId: Long,
    val userId: Long,
    val mealName: String,
    val status: MealStatus,
    val isDeleted: Boolean,
)

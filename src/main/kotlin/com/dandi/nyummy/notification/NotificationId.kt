package com.dandi.nyummy.notification

import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.enum.MealTime
import java.time.Instant
import java.time.LocalDate

object NotificationId {
    fun createMealReminderId(userId: Long, date: LocalDate, mealTime: MealTime): String =
        "meal-reminder:$userId:$date:$mealTime"

    fun createRetentionId(userId: Long, date: LocalDate): String = "retention:$userId:$date"

    // 분석은 시도마다 생기는 사건이라 시각까지 넣는다. 상태만 넣으면 재시도가 또 실패했을 때 앞선 알림과 같은 값이 된다.
    fun createAnalysisId(mealId: Long, status: MealStatus, analyzedAt: Instant): String =
        "analysis:$mealId:$status:${analyzedAt.toEpochMilli()}"
}

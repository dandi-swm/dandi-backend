package com.dandi.nyummy.internal.batch.dto

import com.dandi.nyummy.meal.enum.MealTime
import java.time.Instant

data class MealReminderTarget(
    val userId: Long,
    val catName: String,
    val breakfastHour: Int?,
    val lunchHour: Int?,
    val dinnerHour: Int?,
    val token: String,
) {
    fun convertMealTime(hour: Int): MealTime = when (hour) {
        breakfastHour -> MealTime.BREAKFAST
        lunchHour -> MealTime.LUNCH
        dinnerHour -> MealTime.DINNER
        else -> throw IllegalStateException("알림 시각과 맞는 끼니가 없습니다: userId=$userId, hour=$hour")
    }
}

data class RetentionTarget(val userId: Long, val catName: String, val lastMealAt: Instant, val token: String)

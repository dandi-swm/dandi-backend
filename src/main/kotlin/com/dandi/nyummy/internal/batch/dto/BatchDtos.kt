package com.dandi.nyummy.internal.batch.dto

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.enum.MealTime
import java.time.Instant

data class MealReminderTargetResult(
    val userId: Long,
    val catName: String,
    val breakfastHour: Int?,
    val lunchHour: Int?,
    val dinnerHour: Int?,
    val token: String,
    /** 기기 앱 언어. V0.53 전에 등록한 기기 토큰은 null이고, 원본 언어로 보낸다. */
    val language: Language?,
) {
    fun convertMealTime(hour: Int): MealTime = when (hour) {
        breakfastHour -> MealTime.BREAKFAST
        lunchHour -> MealTime.LUNCH
        dinnerHour -> MealTime.DINNER
        else -> throw IllegalStateException("알림 시각과 맞는 끼니가 없습니다: userId=$userId, hour=$hour")
    }
}

/** @property language 기기 앱 언어. V0.53 전에 등록한 기기 토큰은 null이고, 원본 언어로 보낸다. */
data class RetentionTargetResult(
    val userId: Long,
    val catName: String,
    val lastMealAt: Instant,
    val token: String,
    val language: Language?,
)

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

/**
 * 식사 리마인더 발송 건수. 묶음마다 만들어 더한다.
 *
 * 토큰 목록 대신 건수만 담는다. 목록을 묶음 밖으로 넘기면 FCM 장애로 전부 재시도 대상이 될 때 대상 수만큼 쌓인다.
 */
data class MealReminderSendResult(
    val targetCount: Int,
    val sentCount: Int,
    val successCount: Int,
    val invalidCount: Int,
    val retryableCount: Int,
) {
    operator fun plus(other: MealReminderSendResult): MealReminderSendResult = MealReminderSendResult(
        targetCount = targetCount + other.targetCount,
        sentCount = sentCount + other.sentCount,
        successCount = successCount + other.successCount,
        invalidCount = invalidCount + other.invalidCount,
        retryableCount = retryableCount + other.retryableCount,
    )

    companion object {
        val EMPTY = MealReminderSendResult(
            targetCount = 0,
            sentCount = 0,
            successCount = 0,
            invalidCount = 0,
            retryableCount = 0,
        )
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

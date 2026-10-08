package com.dandi.nyummy.internal.batch

import com.dandi.nyummy.meal.enum.MealTime
import java.time.LocalDate

/**
 * 알림 식별자. 중복 발송 가드의 Redis 키와 같은 값을 쓴다.
 *
 * 서버는 이 값으로 중복 발송을 막고 앱은 같은 값으로 중복 표시를 막으므로,
 * "같은 알림"의 정의가 한 군데에만 있다.
 *
 * UUID가 아니라 결정적으로 만든다 — 배치가 재시도돼도 같은 값이 나와야 양쪽이 같은 알림으로 인식한다.
 * 앱은 이 값을 파싱하지 않고 문자열로만 비교하므로 형식은 서버가 정한다.
 * 다만 형식을 바꾸는 배포는 기존 가드 키를 무효화해 그날 중복 발송을 유발한다.
 */
object NotificationId {
    fun createMealReminderId(userId: Long, date: LocalDate, mealTime: MealTime): String =
        "meal-reminder:$userId:$date:$mealTime"

    fun createRetentionId(userId: Long, date: LocalDate): String = "retention:$userId:$date"
}

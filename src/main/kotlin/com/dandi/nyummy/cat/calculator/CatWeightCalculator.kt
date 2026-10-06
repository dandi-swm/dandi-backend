package com.dandi.nyummy.cat.calculator

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 마지막 평가 이후 지난 평가 구간의 수. 0이면 아직 평가할 구간이 없다.
 *
 * 남은 일수(나머지)는 세지 않는다. 호출자가 구간 수만큼만 평가 경계를 전진시켜야
 * 다음 평가 경계가 밀리지 않는다.
 *
 * 시계 역전이나 timezone 변경으로 [lastEvaluatedDate]가 [today]보다 미래면 음수가 된다.
 * 0으로 보정하지 않으므로 호출자가 `<= 0`으로 걸러야 한다.
 */
fun calculateElapsedIntervals(lastEvaluatedDate: LocalDate, today: LocalDate, intervalDays: Int): Int =
    (ChronoUnit.DAYS.between(lastEvaluatedDate, today) / intervalDays).toInt()

fun calculateWeightStep(totalCalory: Int, targetCalory: Int, tolerance: Double): Int = when {
    totalCalory >= targetCalory * (1 + tolerance) -> 1
    totalCalory < targetCalory * (1 - tolerance) -> -1
    else -> 0
}

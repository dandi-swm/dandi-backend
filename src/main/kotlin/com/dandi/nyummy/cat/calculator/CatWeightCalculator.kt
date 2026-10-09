package com.dandi.nyummy.cat.calculator

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.sign

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

/**
 * 한 평가 구간의 섭취량으로 체형을 몇 칸 움직일지 계산한다. 결과는 항상 -1, 0, 1 중 하나다.
 *
 * 과식은 찌고 소식은 빠진다. 적정 섭취는 유지가 아니라 **보통냥(0) 쪽으로 한 칸**이다.
 * 유지로 두면 뚱냥이가 적정 섭취를 계속해도 영원히 뚱냥이고, 살을 빼려면 소식을 강요하게 된다.
 *
 * 부호만 뒤집는 이유는 한 번에 한 칸씩만 움직이기 위해서다. [currentWeight]를 그대로 쓰면
 * 뚱냥이(+2)가 한 구간에 보통냥까지 점프한다.
 *
 * 기록이 없는 구간은 섭취량 0이므로 소식으로 본다. 기록을 유도하기 위한 의도된 동작이다.
 *
 * 범위를 벗어나는 값(홀쭉냥의 추가 감소 등)은 호출자가 [com.dandi.nyummy.cat.entity.Cat.updateWeightByStep]에서
 * 잘라낸다.
 *
 * @param currentWeight 이 구간을 평가하기 직전의 체형. 여러 구간을 연속 평가할 때는
 *  앞 구간 결과가 반영된 값을 넘겨야 한 칸씩 수렴한다
 */
fun calculateWeightStep(totalCalory: Int, targetCalory: Int, tolerance: Double, currentWeight: Int): Int = when {
    totalCalory >= targetCalory * (1 + tolerance) -> 1
    totalCalory < targetCalory * (1 - tolerance) -> -1
    else -> -currentWeight.sign
}

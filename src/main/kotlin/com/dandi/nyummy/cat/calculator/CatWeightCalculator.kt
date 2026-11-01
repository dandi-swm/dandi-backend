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
 * 한 평가 구간의 기록으로 체형을 몇 칸 움직일지 계산한다. 결과는 항상 -1, 0, 1 중 하나다.
 *
 * 기록이 하나도 없으면 빠지고 과식하면 찐다. 그 밖(적정·소식)은 유지가 아니라 **보통냥(0) 쪽으로 한 칸**이다.
 * 유지로 두면 뚱냥이가 적정 섭취를 계속해도 영원히 뚱냥이고, 살을 빼려면 굶기를 강요하게 된다.
 *
 * 소식을 감소로 보지 않는 이유는 한 끼만 기록한 날도 소식이 되기 때문이다. 하루 단위로 평가하면
 * 기록을 덜 한 사용자의 고양이가 매일 빠진다.
 *
 * 분석에 실패한 식사만 있는 구간은 [hasMeal]이 true이고 [totalCalory]가 0이라 보통냥 쪽으로 움직인다.
 * 분석 실패로 사용자가 손해 보지 않게 하기 위해서다.
 *
 * 부호만 뒤집는 이유는 한 번에 한 칸씩만 움직이기 위해서다. [currentWeight]를 그대로 쓰면
 * 뚱냥이(+2)가 한 구간에 보통냥까지 점프한다.
 *
 * 범위를 벗어나는 값(홀쭉냥의 추가 감소 등)은 호출자가 [com.dandi.nyummy.cat.entity.Cat.updateWeightByStep]에서
 * 잘라낸다.
 *
 * @param hasMeal 이 구간에 삭제되지 않은 식사 기록이 하나라도 있는지. 분석 상태와 무관하다
 * @param totalCalory 분석이 완료된 식사의 칼로리 합
 * @param currentWeight 이 구간을 평가하기 직전의 체형. 여러 구간을 연속 평가할 때는
 *  앞 구간 결과가 반영된 값을 넘겨야 한 칸씩 수렴한다
 */
fun calculateWeightStep(
    hasMeal: Boolean,
    totalCalory: Int,
    targetCalory: Int,
    tolerance: Double,
    currentWeight: Int,
): Int = when {
    !hasMeal -> -1
    totalCalory >= targetCalory * (1 + tolerance) -> 1
    else -> -currentWeight.sign
}

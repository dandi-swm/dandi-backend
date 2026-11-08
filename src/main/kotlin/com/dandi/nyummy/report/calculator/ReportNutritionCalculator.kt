package com.dandi.nyummy.report.calculator

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.report.dto.MacroNutrientBalanceResponse
import com.dandi.nyummy.report.dto.NutritionBalanceResult
import com.dandi.nyummy.report.dto.ReportDayResponse
import com.dandi.nyummy.report.enum.MacroNutrient
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 영양 균형 뷰. 앱의 네이티브 뷰 이름이라 SDUI 칸 이름처럼 PascalCase다 */
const val NUTRITION_BALANCE_VIEW_TYPE = "NutritionBalance"

// 이 시각(포함)부터 끝 시각(제외) 사이에 먹은 식사를 아침으로 센다.
private const val BREAKFAST_START_HOUR = 5
private const val BREAKFAST_END_HOUR = 10

/**
 * 한 주의 식사를 영양 균형 뷰의 숫자와 서버 문장으로 집계한다.
 *
 * [meals]는 그 주에 분석이 완료된 식사(지운 식사 제외)다. 평균은 기록한 날 기준이고, 기록이 없는 날은 칸만 두고 평균에서 뺀다.
 * 강조 영양소는 목표 대비 비율이 100%에서 가장 멀리 벗어난 것이고, 같으면 탄수화물·단백질·지방 순으로 고른다.
 *
 * @param target 하루 목표 섭취량
 * @param zone 식사 시각을 날짜로 바꿀 시간대(Asia/Seoul)
 */
fun calculateNutritionBalance(
    meals: List<Meal>,
    startDate: LocalDate,
    target: Nutrition,
    zone: ZoneId,
): NutritionBalanceResult {
    val mealsByDate = meals.sortedBy { it.mealAt }.groupBy { LocalDate.ofInstant(it.mealAt, zone) }
    val days = (0L until 7L).map { offset ->
        val date = startDate.plusDays(offset)
        val dayMeals = mealsByDate[date].orEmpty()
        val calory = dayMeals.sumOf { it.calory ?: 0 }
        ReportDayResponse(
            date = date,
            dayOfWeek = date.dayOfWeek,
            isRecorded = dayMeals.isNotEmpty(),
            mealCount = dayMeals.size,
            calory = calory,
            foods = dayMeals.map { it.name },
            isOverTarget = dayMeals.isNotEmpty() && calory > target.calory,
        )
    }
    val recordedDayCount = days.count { it.isRecorded }
    val current = calculateDailyAverage(meals, recordedDayCount)
    val macroNutrients = createMacroNutrientBalances(current, target)
    val focusedNutrient = macroNutrients.first { it.isFocused }
    val breakfastDayCount = meals
        .map { it.mealAt.atZone(zone) }
        .filter { it.hour in BREAKFAST_START_HOUR until BREAKFAST_END_HOUR }
        .map { it.toLocalDate() }
        .toSet()
        .size

    return NutritionBalanceResult(
        startDate = startDate,
        mealCount = meals.size,
        recordedDayCount = recordedDayCount,
        current = current,
        target = target,
        macroNutrients = macroNutrients,
        focusedNutrient = focusedNutrient.nutrient,
        days = days,
        breakfastDayCount = breakfastDayCount,
        calorySentence = createCalorySentence(current.calory, target.calory),
        focusSentence = createFocusSentence(focusedNutrient),
        overTargetSentence = createOverTargetSentence(days.filter { it.isOverTarget }.map { it.dayOfWeek }),
        breakfastSentence = "기록한 ${recordedDayCount}일 중 아침을 챙긴 날은 ${breakfastDayCount}일이에요",
    )
}

private fun calculateDailyAverage(meals: List<Meal>, recordedDayCount: Int): Nutrition {
    if (recordedDayCount == 0) return Nutrition.ZERO

    fun average(value: (Meal) -> Int?): Int = (
        meals.sumOf {
            value(it) ?: 0
        }.toDouble() / recordedDayCount
        ).roundToInt()

    return Nutrition(
        calory = average { it.calory },
        carbs = average { it.carbs },
        protein = average { it.protein },
        fat = average { it.fat },
    )
}

private fun createMacroNutrientBalances(current: Nutrition, target: Nutrition): List<MacroNutrientBalanceResponse> {
    val values = listOf(
        Triple(MacroNutrient.CARBS, current.carbs, target.carbs),
        Triple(MacroNutrient.PROTEIN, current.protein, target.protein),
        Triple(MacroNutrient.FAT, current.fat, target.fat),
    )
    val percents = values.map { (_, currentValue, targetValue) -> calculatePercentOfTarget(currentValue, targetValue) }
    // maxBy는 같은 값이면 앞의 것을 고른다(탄수화물·단백질·지방 순).
    val focusedIndex = percents.indices.maxBy { abs(percents[it] - 100) }

    return values.mapIndexed { index, (nutrient, currentValue, targetValue) ->
        MacroNutrientBalanceResponse(
            nutrient = nutrient,
            current = currentValue,
            target = targetValue,
            percentOfTarget = percents[index],
            isFocused = index == focusedIndex,
        )
    }
}

private fun calculatePercentOfTarget(current: Int, target: Int): Int =
    if (target <= 0) 0 else (current * 100.0 / target).roundToInt()

private fun createCalorySentence(current: Int, target: Int): String {
    val difference = current - target
    return when {
        difference > 0 -> "하루 평균 %,dkcal로, 목표보다 %,dkcal 많았어요".format(current, difference)
        difference < 0 -> "하루 평균 %,dkcal로, 목표보다 %,dkcal 적었어요".format(current, -difference)
        else -> "하루 평균 %,dkcal로, 목표와 같았어요".format(current)
    }
}

private fun createFocusSentence(focused: MacroNutrientBalanceResponse): String {
    val percent = focused.percentOfTarget
    return when {
        percent < 100 -> "${focused.nutrient.label}이 목표의 $percent%에서 멈췄어요"
        percent > 100 -> "${focused.nutrient.label}이 목표보다 ${percent - 100}% 많았어요"
        else -> "탄수화물·단백질·지방 모두 목표에 맞았어요"
    }
}

private fun createOverTargetSentence(overTargetDays: List<DayOfWeek>): String = when (overTargetDays.size) {
    0 -> "목표 칼로리를 넘긴 날은 없었어요"
    1 -> "${overTargetDays.single().getDisplayName(TextStyle.FULL, Locale.KOREAN)} 하루만 목표 칼로리를 넘겼어요"
    else -> "${overTargetDays.size}일 목표 칼로리를 넘겼어요"
}

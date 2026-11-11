package com.dandi.nyummy.report.calculator

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.report.dto.MacroNutrientBalanceResponse
import com.dandi.nyummy.report.dto.NutritionBalanceResult
import com.dandi.nyummy.report.dto.ReportDayResponse
import com.dandi.nyummy.report.enum.MacroNutrient
import org.springframework.context.MessageSource
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

// 탄단지 칼로리 구성비가 목표 구성비에서 이 비율 안(±20%)이면 균형이 좋은 주로 보고 강조할 영양소를 두지 않는다.
private const val CALORY_SHARE_TOLERANCE = 0.2

// 1g당 칼로리
private val CALORY_PER_GRAM = mapOf(MacroNutrient.CARBS to 4, MacroNutrient.PROTEIN to 4, MacroNutrient.FAT to 9)

/**
 * 한 주의 식사를 영양 균형 뷰의 숫자와 서버 문장으로 집계한다.
 *
 * [meals]는 그 주에 분석이 완료된 식사(지운 식사 제외)다. 평균은 기록한 날 기준이고, 기록이 없는 날은 칸만 두고 평균에서 뺀다.
 *
 * 강조 영양소는 양이 아니라 구성으로 고른다. 탄단지가 칼로리에서 차지하는 비율(구성비)을 목표 섭취량의 구성비와 비교해,
 * 상대적으로 가장 많이 벗어난 것을 고르고(같으면 탄수화물·단백질·지방 순) 셋 다 ±20% 안이면 고르지 않는다(균형이 좋은 주).
 * 양으로 고르면 적게 먹은 주는 모든 영양소가 모자라 보여, 지방이 많은 주에도 가장 덜 먹은 탄수화물을 강조하게 된다.
 * 많이·적게 먹은 것은 칼로리 문장이 따로 말한다.
 *
 * 서버 문장은 [language]로 만든다. 리포트는 만들 때 한 번 저장하므로, 구매한 요청의 언어로 저장되고 나중에 언어를 바꿔도 그대로다.
 *
 * @param target 하루 목표 섭취량
 * @param zone 식사 시각을 날짜로 바꿀 시간대(Asia/Seoul)
 * @param language 리포트를 구매한 요청의 앱 언어
 * @param messageSource 서버 문장을 꺼낼 서버 고정 문구
 */
fun calculateNutritionBalance(
    meals: List<Meal>,
    startDate: LocalDate,
    target: Nutrition,
    zone: ZoneId,
    language: Language,
    messageSource: MessageSource,
): NutritionBalanceResult {
    val sentences = ReportSentences(language, messageSource)
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
    val focused = macroNutrients.firstOrNull { it.isFocused }
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
        focusedNutrient = focused?.nutrient,
        days = days,
        breakfastDayCount = breakfastDayCount,
        calorySentence = sentences.createCalorySentence(current.calory, target.calory),
        focusSentence = sentences.createFocusSentence(
            focused,
            hasMacroNutrients =
            current.carbs + current.protein + current.fat > 0,
        ),
        overTargetSentence = sentences.createOverTargetSentence(days.filter { it.isOverTarget }.map { it.dayOfWeek }),
        breakfastSentence = sentences.createBreakfastSentence(recordedDayCount, breakfastDayCount),
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
    val currentGrams = mapOf(
        MacroNutrient.CARBS to current.carbs,
        MacroNutrient.PROTEIN to current.protein,
        MacroNutrient.FAT to current.fat,
    )
    val targetGrams = mapOf(
        MacroNutrient.CARBS to target.carbs,
        MacroNutrient.PROTEIN to target.protein,
        MacroNutrient.FAT to target.fat,
    )
    val currentShares = calculateCaloryShares(currentGrams)
    val targetShares = calculateCaloryShares(targetGrams)
    // 목표 구성비 대비 상대 편차. 목표가 20%인 단백질이 10%면 -0.5다.
    val deviations = MacroNutrient.entries.associateWith { nutrient ->
        val targetShare = targetShares.getValue(nutrient)
        if (targetShare <= 0.0) 0.0 else currentShares.getValue(nutrient) / targetShare - 1
    }
    // maxBy는 같은 값이면 앞의 것을 고른다(탄수화물·단백질·지방 순). 기록이 없으면 구성비가 모두 0이라 고르지 않는다.
    val focused = MacroNutrient.entries.maxBy { abs(deviations.getValue(it)) }
        .takeIf { currentGrams.values.sum() > 0 && abs(deviations.getValue(it)) > CALORY_SHARE_TOLERANCE }

    return MacroNutrient.entries.map { nutrient ->
        MacroNutrientBalanceResponse(
            nutrient = nutrient,
            current = currentGrams.getValue(nutrient),
            target = targetGrams.getValue(nutrient),
            percentOfTarget = calculatePercentOfTarget(currentGrams.getValue(nutrient), targetGrams.getValue(nutrient)),
            caloryShare = (currentShares.getValue(nutrient) * 100).roundToInt(),
            targetCaloryShare = (targetShares.getValue(nutrient) * 100).roundToInt(),
            isFocused = nutrient == focused,
        )
    }
}

// 탄단지 각각이 탄단지 칼로리 합에서 차지하는 비율(0~1). 합이 0이면 모두 0이다.
private fun calculateCaloryShares(grams: Map<MacroNutrient, Int>): Map<MacroNutrient, Double> {
    val calories = grams.mapValues { (nutrient, gram) -> gram * CALORY_PER_GRAM.getValue(nutrient) }
    val total = calories.values.sum()
    return calories.mapValues { (_, calory) -> if (total <= 0) 0.0 else calory.toDouble() / total }
}

private fun calculatePercentOfTarget(current: Int, target: Int): Int =
    if (target <= 0) 0 else (current * 100.0 / target).roundToInt()

/**
 * 리포트의 서버 숫자 문장을 [language]로 만든다.
 *
 * 숫자는 MessageFormat에 맡기지 않고 문자열로 넘긴다. MessageFormat은 언어에 따라 자릿수를 다르게 끊어, 지금 문장(1,850kcal)과 달라질 수 있다.
 */
private class ReportSentences(private val language: Language, private val messageSource: MessageSource) {

    fun createCalorySentence(current: Int, target: Int): String {
        val difference = current - target
        return when {
            difference > 0 -> createSentence(
                "report.calorySentence.over",
                formatNumber(current),
                formatNumber(difference),
            )

            difference < 0 -> createSentence(
                "report.calorySentence.under",
                formatNumber(current),
                formatNumber(-difference),
            )

            else -> createSentence("report.calorySentence.equal", formatNumber(current))
        }
    }

    fun createFocusSentence(focused: MacroNutrientBalanceResponse?, hasMacroNutrients: Boolean): String = when {
        !hasMacroNutrients -> createSentence("report.focusSentence.noMacroNutrients")

        focused == null -> createSentence("report.focusSentence.balanced")

        focused.caloryShare < focused.targetCaloryShare -> createSentence(
            "report.focusSentence.under",
            messageSource.getMessage("macroNutrient.${focused.nutrient.name}", language),
            focused.caloryShare.toString(),
            focused.targetCaloryShare.toString(),
        )

        else -> createSentence(
            "report.focusSentence.over",
            messageSource.getMessage("macroNutrient.${focused.nutrient.name}", language),
            focused.caloryShare.toString(),
            focused.targetCaloryShare.toString(),
        )
    }

    fun createOverTargetSentence(overTargetDays: List<DayOfWeek>): String = when (overTargetDays.size) {
        0 -> createSentence("report.overTargetSentence.none")

        1 -> createSentence(
            "report.overTargetSentence.one",
            overTargetDays.single().getDisplayName(TextStyle.FULL, language.toLocale()),
        )

        else -> createSentence("report.overTargetSentence.many", overTargetDays.size.toString())
    }

    fun createBreakfastSentence(recordedDayCount: Int, breakfastDayCount: Int): String =
        createSentence("report.breakfastSentence", recordedDayCount.toString(), breakfastDayCount.toString())

    private fun createSentence(key: String, vararg args: String): String =
        messageSource.getMessage(key, language, *args)

    private fun formatNumber(number: Int): String = "%,d".format(Locale.ROOT, number)
}

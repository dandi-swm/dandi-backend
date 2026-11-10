@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.report.dto

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.report.enum.MacroNutrient
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 영양 균형 뷰([com.dandi.nyummy.report.calculator.NUTRITION_BALANCE_VIEW_TYPE])의 content. reports.content에 JSON으로 저장하고
 * 상세 조회에서 그대로 준다. 숫자가 든 문장(~Sentence)은 서버가 집계값으로 만들고, AI는 [advice]·[suggestions]·[closing]
 * (과 리포트 title)만 쓴다.
 *
 * @property mealCount 그 주에 분석이 완료된 식사 수
 * @property recordedDayCount 식사를 하나라도 기록한 날 수
 * @property current 기록한 날의 하루 평균 섭취량
 * @property target 하루 목표 섭취량(생성 시점 프로필 기준)
 * @property macroNutrients 탄단지 각각의 목표 대비 섭취 비율. 하나가 강조 대상이다
 * @property days 일요일부터 토요일까지 7일
 */
data class NutritionBalanceContentResponse(
    val mealCount: Int,
    val recordedDayCount: Int,
    val current: Nutrition,
    val target: Nutrition,
    val macroNutrients: List<MacroNutrientBalanceResponse>,
    val days: List<ReportDayResponse>,
    val calorySentence: String,
    val focusSentence: String,
    val overTargetSentence: String,
    val breakfastSentence: String,
    val advice: String,
    val suggestions: List<String>,
    val closing: String,
)

/**
 * @property percentOfTarget 하루 평균 섭취량이 목표의 몇 %인지(반올림). 100을 넘을 수 있다
 * @property caloryShare 탄단지 칼로리 합에서 이 영양소가 차지하는 비율(%, 반올림)
 * @property targetCaloryShare 목표 섭취량에서의 같은 비율(%, 반올림)
 * @property isFocused 구성비가 목표에서 가장 많이 벗어나 화면에서 강조하는 영양소. 균형이 좋은 주는 셋 다 false다
 */
data class MacroNutrientBalanceResponse(
    val nutrient: MacroNutrient,
    val current: Int,
    val target: Int,
    val percentOfTarget: Int,
    val caloryShare: Int,
    val targetCaloryShare: Int,
    val isFocused: Boolean,
)

/**
 * @property calory 그날 먹은 칼로리 합. 기록이 없으면 0이고 [isRecorded]가 false다
 * @property foods 그날 먹은 음식 이름(먹은 순서)
 * @property isOverTarget 그날 칼로리가 하루 목표를 넘었다
 */
data class ReportDayResponse(
    val date: LocalDate,
    val dayOfWeek: DayOfWeek,
    val isRecorded: Boolean,
    val mealCount: Int,
    val calory: Int,
    val foods: List<String>,
    val isOverTarget: Boolean,
)

/**
 * 식사 집계 결과. AI 문장을 받기 전의 서버 몫이다.
 *
 * @property focusedNutrient 구성비가 목표에서 가장 많이 벗어난 영양소. 균형이 좋은 주(또는 기록이 없는 주)는 null이다.
 *   AI가 같은 영양소를 말하도록 프롬프트에 넘긴다
 */
data class NutritionBalanceResult(
    val startDate: LocalDate,
    val mealCount: Int,
    val recordedDayCount: Int,
    val current: Nutrition,
    val target: Nutrition,
    val macroNutrients: List<MacroNutrientBalanceResponse>,
    val focusedNutrient: MacroNutrient?,
    val days: List<ReportDayResponse>,
    val breakfastDayCount: Int,
    val calorySentence: String,
    val focusSentence: String,
    val overTargetSentence: String,
    val breakfastSentence: String,
)

/**
 * AI가 쓴 리포트 문장. 숫자는 쓰지 않는다(숫자 문장은 서버 몫).
 *
 * @property title 리포트 한 줄 요약
 * @property advice 강조 영양소에 대한 한 줄 조언
 * @property suggestions 다음 주에 해 볼 제안 3개
 * @property closing 고양이 말투 마무리 한 줄
 */
data class ReportTextResult(val title: String, val advice: String, val suggestions: List<String>, val closing: String)

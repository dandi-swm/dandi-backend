package com.dandi.nyummy.report.calculator

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.report.enum.MacroNutrient
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportNutritionCalculatorTest {

    private val zone = ZoneId.of("Asia/Seoul")

    // 2026-11-01(일) ~ 11-07(토)
    private val sunday = LocalDate.parse("2026-11-01")
    private val target = Nutrition(calory = 2000, carbs = 250, protein = 100, fat = 66)

    private fun meal(
        kstDateTime: String,
        calory: Int,
        carbs: Int = 0,
        protein: Int = 0,
        fat: Int = 0,
        name: String = "밥",
    ) = Meal(
        status = MealStatus.COMPLETED,
        imageKey = "meals/1/$kstDateTime.jpg",
        mealAt = LocalDate.parse(kstDateTime.substring(0, 10))
            .atTime(kstDateTime.substring(11, 13).toInt(), 0)
            .atZone(zone)
            .toInstant(),
        userId = 1L,
    ).also {
        it.name = name
        it.calory = calory
        it.carbs = carbs
        it.protein = protein
        it.fat = fat
    }

    private fun calculate(vararg meals: Meal) = calculateNutritionBalance(meals.toList(), sunday, target, zone)

    @Test
    fun `일요일부터 7일 칸을 만들고 기록한 날의 칼로리와 음식을 먹은 순서로 채운다`() {
        val result = calculate(
            meal("2026-11-01T12", 700, name = "라면"),
            meal("2026-11-01T08", 400, name = "토스트"),
            meal("2026-11-03T19", 900, name = "불고기"),
        )

        assertEquals((0L until 7L).map { sunday.plusDays(it) }, result.days.map { it.date })
        assertEquals(DayOfWeek.SUNDAY, result.days.first().dayOfWeek)
        assertEquals(listOf("토스트", "라면"), result.days[0].foods)
        assertEquals(1100, result.days[0].calory)
        assertFalse(result.days[1].isRecorded)
        assertEquals(0, result.days[1].calory)
        assertEquals(3, result.mealCount)
        assertEquals(2, result.recordedDayCount)
    }

    @Test
    fun `KST 자정을 기준으로 날짜를 나눈다`() {
        // 11-01 23:00 KST와 11-02 00:00 KST는 서로 다른 날이다.
        val late = meal("2026-11-01T23", 500)
        val midnight = meal("2026-11-02T00", 500)

        val result = calculate(late, midnight)

        assertEquals(1, result.days[0].mealCount)
        assertEquals(1, result.days[1].mealCount)
    }

    @Test
    fun `하루 평균은 기록한 날로만 나눈다`() {
        val result = calculate(
            meal("2026-11-01T12", 1500, carbs = 200, protein = 50, fat = 40),
            meal("2026-11-02T12", 2500, carbs = 300, protein = 70, fat = 60),
        )

        assertEquals(Nutrition(calory = 2000, carbs = 250, protein = 60, fat = 50), result.current)
    }

    // 목표(탄 250·단 100·지 66g)의 칼로리 구성비는 50·20·30%다.

    @Test
    fun `양으로는 탄수화물이 가장 모자라도 칼로리 구성에서 지방이 가장 넘치면 지방을 강조한다`() {
        // 양: 탄수화물 34%·단백질 55%·지방 121%, 구성비: 27·17·56%
        val result = calculate(meal("2026-11-01T12", 1300, carbs = 85, protein = 55, fat = 80))

        assertEquals(listOf(34, 55, 121), result.macroNutrients.map { it.percentOfTarget })
        assertEquals(listOf(27, 17, 56), result.macroNutrients.map { it.caloryShare })
        assertEquals(listOf(50, 20, 30), result.macroNutrients.map { it.targetCaloryShare })
        assertEquals(MacroNutrient.FAT, result.focusedNutrient)
        assertEquals(listOf(false, false, true), result.macroNutrients.map { it.isFocused })
        assertEquals("지방이 칼로리의 56%로, 목표 30%보다 많았어요", result.focusSentence)
    }

    @Test
    fun `목표 비율보다 상대적으로 가장 적은 영양소면 적었다고 쓴다`() {
        // 구성비 68·10·23%: 단백질은 목표의 절반(-52%), 탄수화물은 +35%라 단백질이 더 멀다
        val result = calculate(meal("2026-11-01T12", 1050, carbs = 175, protein = 25, fat = 26))

        assertEquals(MacroNutrient.PROTEIN, result.focusedNutrient)
        assertEquals("단백질이 칼로리의 10%로, 목표 20%보다 적었어요", result.focusSentence)
    }

    @Test
    fun `셋 다 목표 비율에서 20퍼센트 안이면 강조하지 않고 균형이 좋았다고 쓴다`() {
        // 양은 목표보다 적지만(80·85·76%) 구성비는 50·21·28%로 고르다
        val result = calculate(meal("2026-11-01T12", 1600, carbs = 200, protein = 85, fat = 50))

        assertEquals(null, result.focusedNutrient)
        assertEquals(listOf(false, false, false), result.macroNutrients.map { it.isFocused })
        assertEquals("탄수화물·단백질·지방을 고르게 먹었어요", result.focusSentence)
    }

    @Test
    fun `탄단지 기록이 없으면 강조하지 않고 볼 수 없다고 쓴다`() {
        val result = calculate()

        assertEquals(null, result.focusedNutrient)
        assertEquals(listOf(0, 0, 0), result.macroNutrients.map { it.caloryShare })
        assertEquals("탄수화물·단백질·지방 기록이 없어 균형을 볼 수 없어요", result.focusSentence)
    }

    @Test
    fun `하루 평균 칼로리를 목표와 비교한 문장을 쓴다`() {
        assertEquals("하루 평균 1,640kcal로, 목표보다 360kcal 적었어요", calculate(meal("2026-11-01T12", 1640)).calorySentence)
        assertEquals("하루 평균 2,100kcal로, 목표보다 100kcal 많았어요", calculate(meal("2026-11-01T12", 2100)).calorySentence)
        assertEquals("하루 평균 2,000kcal로, 목표와 같았어요", calculate(meal("2026-11-01T12", 2000)).calorySentence)
    }

    @Test
    fun `목표 칼로리를 넘긴 날을 요일로 센다`() {
        assertEquals("목표 칼로리를 넘긴 날은 없었어요", calculate(meal("2026-11-01T12", 2000)).overTargetSentence)

        val oneDay = calculate(meal("2026-11-01T12", 1500), meal("2026-11-07T12", 2500))
        assertEquals("토요일 하루만 목표 칼로리를 넘겼어요", oneDay.overTargetSentence)
        assertTrue(oneDay.days[6].isOverTarget)

        val twoDays = calculate(meal("2026-11-02T12", 2500), meal("2026-11-07T12", 2500))
        assertEquals("2일 목표 칼로리를 넘겼어요", twoDays.overTargetSentence)
    }

    @Test
    fun `5시부터 10시 전까지 먹은 날을 아침을 챙긴 날로 센다`() {
        val result = calculate(
            meal("2026-11-01T05", 300),
            meal("2026-11-01T08", 300),
            meal("2026-11-02T09", 300),
            meal("2026-11-03T10", 300),
            meal("2026-11-04T04", 300),
        )

        assertEquals(2, result.breakfastDayCount)
        assertEquals("기록한 4일 중 아침을 챙긴 날은 2일이에요", result.breakfastSentence)
    }
}

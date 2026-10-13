package com.dandi.nyummy.meal.calculator

import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.DailyNutritionEvaluation
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.enum.Gender
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class NutritionCalculatorTest {

    private val today = LocalDate.of(2026, 7, 16)
    private val defaultIntake = Nutrition(calory = 2000, carbs = 250, protein = 100, fat = 70)

    // application.yaml의 app.meal 기본값과 같다 (80~120%, 탄단지 3종 중 2개)
    private val intakeTolerance = 0.2
    private val minPositiveMacroCount = 2

    private fun evaluate(meals: List<Meal>) =
        calculateDailyNutritionEvaluation(meals, defaultIntake, intakeTolerance, minPositiveMacroCount)

    private fun createProfile(
        birth: LocalDate? = LocalDate.of(2001, 3, 15),
        gender: Gender? = Gender.MALE,
        height: Int? = 175,
        weight: Int? = 70,
    ) = Profile(nickname = "냠미", birth = birth, gender = gender, height = height, weight = weight, userId = 1L)

    private fun createMeal(calory: Int? = null, carbs: Int? = null, protein: Int? = null, fat: Int? = null) =
        Meal(status = MealStatus.COMPLETED, imageKey = "meals/1/image.jpg", mealAt = Instant.EPOCH, userId = 1L)
            .apply {
                this.calory = calory
                this.carbs = carbs
                this.protein = protein
                this.fat = fat
            }

    // calculateRecommendedDailyIntake

    @Test
    fun `프로필이 null이면 기본 권장량을 반환한다`() {
        val result = calculateRecommendedDailyIntake(null, today)

        assertEquals(defaultIntake, result)
    }

    @Test
    fun `생년월일이 없으면 기본 권장량을 반환한다`() {
        val result = calculateRecommendedDailyIntake(createProfile(birth = null), today)

        assertEquals(defaultIntake, result)
    }

    @Test
    fun `키가 없으면 기본 권장량을 반환한다`() {
        val result = calculateRecommendedDailyIntake(createProfile(height = null), today)

        assertEquals(defaultIntake, result)
    }

    @Test
    fun `몸무게가 없으면 기본 권장량을 반환한다`() {
        val result = calculateRecommendedDailyIntake(createProfile(weight = null), today)

        assertEquals(defaultIntake, result)
    }

    @Test
    fun `성별이 OTHER이면 기본 권장량을 반환한다`() {
        val result = calculateRecommendedDailyIntake(createProfile(gender = Gender.OTHER), today)

        assertEquals(defaultIntake, result)
    }

    @Test
    fun `성별이 없으면 기본 권장량을 반환한다`() {
        val result = calculateRecommendedDailyIntake(createProfile(gender = null), today)

        assertEquals(defaultIntake, result)
    }

    @Test
    fun `남성 프로필의 권장량을 Mifflin-St Jeor 공식으로 계산한다`() {
        // 만 25세, 175cm, 70kg 남성
        // BMR = 10*70 + 6.25*175 - 5*25 + 5 = 1673.75
        // 칼로리 = 1673.75 * 1.375 = 2301.40625 (소수 절삭)
        val result = calculateRecommendedDailyIntake(createProfile(gender = Gender.MALE), today)

        assertEquals(Nutrition(calory = 2301, carbs = 287, protein = 115, fat = 76), result)
    }

    @Test
    fun `여성 프로필의 권장량을 Mifflin-St Jeor 공식으로 계산한다`() {
        // 만 25세, 175cm, 70kg 여성
        // BMR = 10*70 + 6.25*175 - 5*25 - 161 = 1507.75
        // 칼로리 = 1507.75 * 1.375 = 2073.15625 (소수 절삭)
        val result = calculateRecommendedDailyIntake(createProfile(gender = Gender.FEMALE), today)

        assertEquals(Nutrition(calory = 2073, carbs = 259, protein = 103, fat = 69), result)
    }

    @Test
    fun `생일 당일부터 만 나이가 올라가 권장 칼로리가 줄어든다`() {
        val birthdayToday = createProfile(birth = LocalDate.of(2001, 7, 16))
        val birthdayTomorrow = createProfile(birth = LocalDate.of(2001, 7, 17))

        assertEquals(2301, calculateRecommendedDailyIntake(birthdayToday, today).calory)
        assertEquals(2308, calculateRecommendedDailyIntake(birthdayTomorrow, today).calory)
    }

    // calculateDailyNutritionEvaluation

    @Test
    fun `식사가 없으면 UNRECORDED다`() {
        val result = evaluate(emptyList())

        assertEquals(DailyNutritionEvaluation.UNRECORDED, result)
    }

    @Test
    fun `한 끼가 정확히 권장량이면 POSITIVE다`() {
        val meals = listOf(createMeal(calory = 2000, carbs = 250, protein = 100, fat = 70))

        val result = evaluate(meals)

        assertEquals(DailyNutritionEvaluation.POSITIVE, result)
    }

    @Test
    fun `여러 끼는 합산해서 평가한다`() {
        // 각각은 권장량의 50%라 단독으로는 NEGATIVE지만 합산하면 100%
        val meals = listOf(
            createMeal(calory = 1000, carbs = 125, protein = 50, fat = 35),
            createMeal(calory = 1000, carbs = 125, protein = 50, fat = 35),
        )

        val result = evaluate(meals)

        assertEquals(DailyNutritionEvaluation.POSITIVE, result)
    }

    @Test
    fun `정확히 80퍼센트는 하한에 포함되어 POSITIVE다`() {
        val meals = listOf(createMeal(calory = 1600, carbs = 200, protein = 80, fat = 56))

        assertEquals(DailyNutritionEvaluation.POSITIVE, evaluate(meals))
    }

    @Test
    fun `80퍼센트 미만이면 NEGATIVE다`() {
        val meals = listOf(createMeal(calory = 1599, carbs = 199, protein = 79, fat = 55))

        assertEquals(DailyNutritionEvaluation.NEGATIVE, evaluate(meals))
    }

    @Test
    fun `정확히 120퍼센트는 상한에 포함되어 POSITIVE다`() {
        val meals = listOf(createMeal(calory = 2400, carbs = 300, protein = 120, fat = 84))

        assertEquals(DailyNutritionEvaluation.POSITIVE, evaluate(meals))
    }

    @Test
    fun `120퍼센트를 넘으면 NEGATIVE다`() {
        val meals = listOf(createMeal(calory = 2401, carbs = 301, protein = 121, fat = 85))

        assertEquals(DailyNutritionEvaluation.NEGATIVE, evaluate(meals))
    }

    @Test
    fun `탄단지 중 하나가 범위를 벗어나도 둘이 통과하면 POSITIVE다`() {
        val meals = listOf(createMeal(calory = 2000, carbs = 250, protein = 100, fat = 0))

        assertEquals(DailyNutritionEvaluation.POSITIVE, evaluate(meals))
    }

    @Test
    fun `탄단지 중 둘이 범위를 벗어나면 NEGATIVE다`() {
        val meals = listOf(createMeal(calory = 2000, carbs = 250, protein = 0, fat = 0))

        assertEquals(DailyNutritionEvaluation.NEGATIVE, evaluate(meals))
    }

    @Test
    fun `칼로리가 범위를 벗어나면 탄단지가 모두 통과해도 NEGATIVE다`() {
        val meals = listOf(createMeal(calory = 1500, carbs = 250, protein = 100, fat = 70))

        assertEquals(DailyNutritionEvaluation.NEGATIVE, evaluate(meals))
    }

    @Test
    fun `분석 전 식사는 영양을 0으로 합산한다`() {
        val meals = listOf(
            createMeal(calory = 2000, carbs = 250, protein = 100, fat = 70),
            createMeal(),
        )

        val result = evaluate(meals)

        assertEquals(DailyNutritionEvaluation.POSITIVE, result)
    }
}

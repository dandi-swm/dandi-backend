package com.dandi.nyummy.cat.service

import com.dandi.nyummy.cat.config.CatProperties
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.repository.CatAnimationLoader
import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CatErrorCode
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.profile.repository.ProfileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 프로필을 null로 고정해 목표 섭취량을 기본 권장량(2000 * 3일 = 6000)으로 단순화한다.
 * 따라서 증가 임계는 7200(120%), 감소 임계는 4800(80%)이다.
 * 프로필 기반 권장량 계산 자체는 NutritionCalculator의 책임이다.
 */
class CatServiceTest {

    private val catRepository: CatRepository = mockk()
    private val mealRepository: MealRepository = mockk()
    private val profileRepository: ProfileRepository = mockk()
    private val catProperties = CatProperties(
        weightUpdateIntervalDays = 3,
        weightUpdateTolerance = 0.2,
    )
    private val catAnimationLoader: CatAnimationLoader = mockk()

    private val zone = ZoneId.of("Asia/Seoul")

    /** 2026-09-27 19:30 KST로 시각을 고정해, 자정 경계와 무관하게 항상 같은 결과가 나오게 한다. */
    private val now: Instant = LocalDateTime.of(2026, 9, 27, 19, 30).atZone(zone).toInstant()
    private val clock: Clock = Clock.fixed(now, zone)
    private val today: LocalDate = LocalDate.of(2026, 9, 27)

    private val catService = CatService(
        catRepository = catRepository,
        mealRepository = mealRepository,
        profileRepository = profileRepository,
        catProperties = catProperties,
        clock = clock,
        catAnimationLoader = catAnimationLoader,
    )

    /** 평가 구간의 종료 시각. 갱신 후 weightUpdatedAt이 이 값이어야 한다. */
    private val expectedEnd: Instant = today.atStartOfDay(zone).toInstant()

    private val userId = 1L
    private val dueTime: Instant = now.minus(4, ChronoUnit.DAYS)
    private val notDueTime: Instant = now.minus(1, ChronoUnit.DAYS)

    @Test
    @DisplayName("고양이가 존재하지 않으면 CAT_NOT_FOUND 예외가 발생한다")
    fun updateCatWeight_catNotFound() {
        // given
        every { catRepository.findByUserId(userId) } returns null

        // when & then
        assertThatThrownBy { catService.updateCatWeight(userId) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", CatErrorCode.CAT_NOT_FOUND)
    }

    @Test
    @DisplayName("체형 변화 주기가 지나지 않았으면 식사를 조회하지 않고 체형과 변화 시각을 그대로 둔다")
    fun updateCatWeight_notDue() {
        // given: 1일 전에 평가된 고양이 (주기는 3일)
        val cat = createCat(weightUpdatedAt = notDueTime)

        every { catRepository.findByUserId(userId) } returns cat

        // when
        val response = catService.updateCatWeight(userId)

        // then
        assertThat(cat.weight).isEqualTo(0)
        assertThat(cat.weightUpdatedAt).isEqualTo(notDueTime)
        assertThat(response.weight).isEqualTo(0)
        verify(exactly = 0) { mealRepository.getMealsByUserIdAndPeriod(any(), any(), any()) }
    }

    @Test
    @DisplayName("체형 변화 주기가 지나고 목표의 120% 이상 섭취했으면 체형이 1단계 증가하고 변화 시각이 구간 종료 시각으로 갱신된다")
    fun updateCatWeight_surplus() {
        // given: 목표 6000, 섭취 8000 (증가 임계 7200 초과)
        val cat = createCat(weightUpdatedAt = dueTime)
        val meals = listOf(
            createMeal(calory = 4000),
            createMeal(calory = 4000),
        )

        every { catRepository.findByUserId(userId) } returns cat
        every { mealRepository.getMealsByUserIdAndPeriod(userId, any(), any()) } returns meals
        every { profileRepository.getProfileByUserId(userId) } returns null

        // when
        val response = catService.updateCatWeight(userId)

        // then
        assertThat(cat.weight).isEqualTo(1)
        assertThat(cat.weightUpdatedAt).isEqualTo(expectedEnd)
        assertThat(response.weight).isEqualTo(1)
        assertThat(response.weightDescription).isEqualTo("통통냥")
    }

    @Test
    @DisplayName("구간에 식사 기록이 없으면 섭취량 0으로 취급되어 체형이 1단계 감소한다")
    fun updateCatWeight_deficit() {
        // given: 목표 6000, 섭취 0 (감소 임계 4800 미만)
        val cat = createCat(weightUpdatedAt = dueTime)

        every { catRepository.findByUserId(userId) } returns cat
        every { mealRepository.getMealsByUserIdAndPeriod(userId, any(), any()) } returns emptyList()
        every { profileRepository.getProfileByUserId(userId) } returns null

        // when
        val response = catService.updateCatWeight(userId)

        // then
        assertThat(cat.weight).isEqualTo(-1)
        assertThat(cat.weightUpdatedAt).isEqualTo(expectedEnd)
        assertThat(response.weightDescription).isEqualTo("날씬냥")
    }

    @Test
    @DisplayName("분석이 완료되지 않은 식사는 섭취량 집계에서 제외된다")
    fun updateCatWeight_excludesIncompleteMeals() {
        // given: COMPLETED 6000만 집계하면 유지 구간(4800~7200)이지만,
        //        FAILED 3000까지 더해지면 9000이 되어 증가 임계를 넘는다.
        val cat = createCat(weightUpdatedAt = dueTime)
        val meals = listOf(
            createMeal(calory = 6000, status = MealStatus.COMPLETED),
            createMeal(calory = 3000, status = MealStatus.FAILED),
        )

        every { catRepository.findByUserId(userId) } returns cat
        every { mealRepository.getMealsByUserIdAndPeriod(userId, any(), any()) } returns meals
        every { profileRepository.getProfileByUserId(userId) } returns null

        // when
        catService.updateCatWeight(userId)

        // then
        assertThat(cat.weight).isEqualTo(0)
    }

    @Test
    @DisplayName("이미 최대 체형이면 과식해도 더 증가하지 않는다")
    fun updateCatWeight_clampedAtMaxWeight() {
        // given: 뚱냥이(2) 상태에서 목표의 120%를 초과해 섭취
        val cat = createCat(weight = 2, weightUpdatedAt = dueTime)

        every { catRepository.findByUserId(userId) } returns cat
        every { mealRepository.getMealsByUserIdAndPeriod(userId, any(), any()) } returns
            listOf(createMeal(calory = 20000))
        every { profileRepository.getProfileByUserId(userId) } returns null

        // when
        val response = catService.updateCatWeight(userId)

        // then
        assertThat(cat.weight).isEqualTo(2)
        assertThat(response.weightDescription).isEqualTo("뚱냥이")
    }

    @Test
    @DisplayName("이미 최소 체형이면 굶어도 더 감소하지 않는다")
    fun updateCatWeight_clampedAtMinWeight() {
        // given: 홀쭉냥(-2) 상태에서 기록 없음
        val cat = createCat(weight = -2, weightUpdatedAt = dueTime)

        every { catRepository.findByUserId(userId) } returns cat
        every { mealRepository.getMealsByUserIdAndPeriod(userId, any(), any()) } returns emptyList()
        every { profileRepository.getProfileByUserId(userId) } returns null

        // when
        val response = catService.updateCatWeight(userId)

        // then
        assertThat(cat.weight).isEqualTo(-2)
        assertThat(response.weightDescription).isEqualTo("홀쭉냥")
    }

    /**
     * weight와 weightUpdatedAt은 [Cat.updateWeight]로만 바꿀 수 있으므로,
     * 초기 체형은 0에서의 변화량으로 지정한다.
     */
    private fun createCat(userId: Long = this.userId, weight: Int = 0, weightUpdatedAt: Instant = Instant.now()): Cat =
        Cat(name = "나비", userId = userId)
            .apply { updateWeight(weight, weightUpdatedAt) }

    private fun createMeal(
        calory: Int,
        status: MealStatus = MealStatus.COMPLETED,
        userId: Long = this.userId,
        imageKey: String = "test-image.jpg",
        mealAt: Instant = now,
    ): Meal = Meal(
        status = status,
        imageKey = imageKey,
        mealAt = mealAt,
        userId = userId,
    ).apply { this.calory = calory }
}

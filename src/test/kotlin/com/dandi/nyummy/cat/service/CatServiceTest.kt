package com.dandi.nyummy.cat.service

import com.dandi.nyummy.cat.config.CatProperties
import com.dandi.nyummy.cat.dto.CatAnimationResponse
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.enum.CatWeight
import com.dandi.nyummy.cat.repository.CatAnimationLoader
import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CatErrorCode
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.repository.ProfileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 프로필을 null로 고정해 목표 섭취량을 기본 권장량(2000 * 3일 = 6000)으로 단순화한다.
 * 따라서 한 구간의 증가 임계는 7200(120%), 감소 임계는 4800(80%)이다.
 * 프로필 기반 권장량 계산 자체는 NutritionCalculator의 책임이다.
 *
 * 평가 창은 `weightUpdatedAt`을 기준으로 잡히므로, 테스트는 "며칠 전에 평가됐는지"로 상태를
 * 지정하고 갱신 후 `weightUpdatedAt`이 어느 구간 경계로 전진했는지까지 확인한다.
 */
class CatServiceTest {

    private val catRepository: CatRepository = mockk()
    private val mealRepository: MealRepository = mockk()
    private val profileRepository: ProfileRepository = mockk()
    private val catAnimationLoader: CatAnimationLoader = mockk()
    private val catProperties = CatProperties(
        weightUpdateIntervalDays = 3,
        weightUpdateTolerance = 0.2,
    )

    private val zone = ZoneId.of("Asia/Seoul")

    /** 2026-09-27 19:30 KST로 시각을 고정해, 자정 경계와 무관하게 항상 같은 결과가 나오게 한다. */
    private val now: Instant = LocalDateTime.of(2026, 9, 27, 19, 30).atZone(zone).toInstant()
    private val clock: Clock = Clock.fixed(now, zone)
    private val today: LocalDate = LocalDate.of(2026, 9, 27)

    private val userId = 1L

    private val catService = CatService(
        catRepository = catRepository,
        mealRepository = mealRepository,
        profileRepository = profileRepository,
        catProperties = catProperties,
        clock = clock,
        catAnimationLoader = catAnimationLoader,
    )

    @BeforeEach
    fun setUp() {
        every { profileRepository.getProfileByUserId(userId) } returns null
        givenMeals()
    }

    // ---------------------------------------------------------------- 평가하지 않는 경우

    @Test
    @DisplayName("평가 주기가 지나지 않았으면 식사를 조회하지 않고 체형과 평가 시각을 그대로 둔다")
    fun notDue() {
        // given: 2일 전에 평가 (주기는 3일)
        val cat = givenCat(evaluatedDaysAgo = 2)

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(0)
        assertThat(cat.weightUpdatedAt).isEqualTo(midnight(2))
        verify(exactly = 0) { mealRepository.getMealsByUserIdAndPeriod(any(), any(), any()) }
    }

    @Test
    @DisplayName("오늘 이미 평가했으면 아무것도 하지 않는다")
    fun evaluatedToday() {
        // given
        val cat = givenCat(evaluatedDaysAgo = 0)

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(0)
        assertThat(cat.weightUpdatedAt).isEqualTo(midnight(0))
        verify(exactly = 0) { mealRepository.getMealsByUserIdAndPeriod(any(), any(), any()) }
    }

    @Test
    @DisplayName("평가 시각이 미래면 평가하지 않는다")
    fun evaluatedInFuture() {
        // given: 시계 역전이나 timezone 변경으로 생길 수 있다.
        //        지난 구간이 음수가 되므로 구간 배열 크기가 음수가 되어 터지지 않아야 한다.
        val cat = givenCat(evaluatedDaysAgo = -3)

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(0)
        assertThat(cat.weightUpdatedAt).isEqualTo(midnight(-3))
        verify(exactly = 0) { mealRepository.getMealsByUserIdAndPeriod(any(), any(), any()) }
    }

    // ---------------------------------------------------------------- 한 구간

    @Test
    @DisplayName("한 구간을 과식했으면 체형이 한 단계 올라가고 평가 시각이 구간 종료일로 전진한다")
    fun singleIntervalSurplus() {
        // given: 4일 전에 평가 → 지난 구간 1개, 평가 창은 [4일 전, 1일 전)
        val cat = givenCat(evaluatedDaysAgo = 4)
        givenMeals(meal(daysAgo = 3, calory = 8000)) // 증가 임계 7200 초과

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(1)
        assertThat(cat.weightUpdatedAt).isEqualTo(midnight(1))
        verify { mealRepository.getMealsByUserIdAndPeriod(userId, midnight(4), midnight(1)) }
    }

    @Test
    @DisplayName("기록이 없는 구간은 섭취량 0으로 취급되어 체형이 한 단계 내려간다")
    fun singleIntervalDeficit() {
        // given: 3일 전에 평가 → 평가 창은 [3일 전, 오늘)
        val cat = givenCat(evaluatedDaysAgo = 3)

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-1)
        assertThat(cat.weightUpdatedAt).isEqualTo(midnight(0))
    }

    @Test
    @DisplayName("분석이 완료되지 않은 식사는 섭취량 집계에서 제외된다")
    fun excludesIncompleteMeals() {
        // given: COMPLETED 6000만 집계하면 유지 구간(4800~7200)이지만,
        //        FAILED 3000까지 더해지면 9000이 되어 증가 임계를 넘는다.
        val cat = givenCat(evaluatedDaysAgo = 3)
        givenMeals(
            meal(daysAgo = 2, calory = 6000, status = MealStatus.COMPLETED),
            meal(daysAgo = 1, calory = 3000, status = MealStatus.FAILED),
        )

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(0)
    }

    @Test
    @DisplayName("칼로리가 비어 있는 완료 식사는 0으로 집계된다")
    fun treatsNullCaloryAsZero() {
        // given: 상태는 COMPLETED지만 calory가 null인 경우 (분석 결과 누락)
        val cat = givenCat(evaluatedDaysAgo = 3)
        givenMeals(meal(daysAgo = 2, calory = null))

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-1)
    }

    @Test
    @DisplayName("이미 최대 체형이면 과식해도 더 증가하지 않는다")
    fun clampedAtMaxWeight() {
        // given: 뚱냥이(2) 상태에서 목표의 120%를 초과해 섭취
        val cat = givenCat(evaluatedDaysAgo = 3, weight = 2)
        givenMeals(meal(daysAgo = 2, calory = 20000))

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(2)
    }

    @Test
    @DisplayName("이미 최소 체형이면 굶어도 더 감소하지 않는다")
    fun clampedAtMinWeight() {
        // given: 홀쭉냥(-2) 상태에서 기록 없음
        val cat = givenCat(evaluatedDaysAgo = 3, weight = -2)

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-2)
    }

    // ---------------------------------------------------------------- 적정 섭취 수렴

    @Test
    @DisplayName("뚱냥이가 적정 섭취하면 보통냥 쪽으로 한 칸만 내려간다")
    fun convergesOneStepDownward() {
        // given: 뚱냥이(2)가 한 구간 동안 목표(6000)만큼 먹었다. 유지가 아니라 한 칸 내려가야 한다.
        val cat = givenCat(evaluatedDaysAgo = 3, weight = 2)
        givenMeals(meal(daysAgo = 2, calory = 6000))

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(1)
    }

    @Test
    @DisplayName("홀쭉냥이 적정 섭취하면 보통냥 쪽으로 한 칸 올라간다")
    fun convergesOneStepUpward() {
        // given: 홀쭉냥(-2)이 목표만큼 먹었다. 소식이 아니므로 올라간다.
        val cat = givenCat(evaluatedDaysAgo = 3, weight = -2)
        givenMeals(meal(daysAgo = 2, calory = 6000))

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-1)
    }

    @Test
    @DisplayName("보통냥이 적정 섭취하면 체형이 유지된다")
    fun staysAtNormalWeight() {
        // given: 수렴의 종착점이므로 더 움직이지 않는다.
        val cat = givenCat(evaluatedDaysAgo = 3, weight = 0)
        givenMeals(meal(daysAgo = 2, calory = 6000))

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(0)
    }

    @Test
    @DisplayName("뚱냥이가 세 구간 내내 적정 섭취하면 한 칸씩 내려와 보통냥에서 멈춘다")
    fun convergesOneStepPerIntervalAndStopsAtNormal() {
        // given: 뚱냥이(2)가 9일(3구간) 동안 매 구간 목표(6000)만큼 먹었다.
        //        steps = [-1, -1, 0] → 1, 0, 0 이므로 최종 0.
        //        현재 체형을 루프 밖에서 한 번만 읽으면 [-1, -1, -1]이 되어 -1까지 지나간다.
        val cat = givenCat(evaluatedDaysAgo = 9, weight = 2)
        givenMeals(
            meal(daysAgo = 8, calory = 6000), // 첫 구간 [9일 전, 6일 전)
            meal(daysAgo = 5, calory = 6000), // 둘째 구간 [6일 전, 3일 전)
            meal(daysAgo = 2, calory = 6000), // 셋째 구간 [3일 전, 오늘)
        )

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(0)
    }

    // ---------------------------------------------------------------- 구간 누적

    @Test
    @DisplayName("6일 굶으면 두 구간이 적용되어 두 단계 내려간다")
    fun accumulatesTwoIntervals() {
        // given: 6일 전에 평가 → 지난 구간 2개, 평가 창은 [6일 전, 오늘)
        val cat = givenCat(evaluatedDaysAgo = 6)

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-2)
        assertThat(cat.weightUpdatedAt).isEqualTo(midnight(0))
        verify { mealRepository.getMealsByUserIdAndPeriod(userId, midnight(6), midnight(0)) }
    }

    @Test
    @DisplayName("9일 굶으면 세 구간이 적용되지만 최소 체형에서 멈춘다")
    fun accumulationStopsAtMinWeight() {
        // given: 보통냥(0)에서 시작하면 두 구간 만에 바닥에 닿는다.
        val cat = givenCat(evaluatedDaysAgo = 9)

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-2)
    }

    @Test
    @DisplayName("뚱냥이가 12일 굶으면 네 구간이 모두 적용되어 홀쭉냥까지 내려간다")
    fun accumulatesFullWeightRange() {
        // given: 체형 폭(4단계)을 전부 쓰는 유일한 경우
        val cat = givenCat(evaluatedDaysAgo = 12, weight = 2)

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-2)
        assertThat(cat.weightUpdatedAt).isEqualTo(midnight(0))
    }

    @Test
    @DisplayName("섭취량은 구간별로 나눠 평가한다")
    fun bucketsCaloryPerInterval() {
        // given: 6일 전 평가(2구간). 두 구간에 4000씩 나눠 먹었다.
        //        구간별로 보면 둘 다 감소 임계(4800) 미만이라 -2가 된다.
        //        버킷팅이 깨져 한 구간에 8000이 몰리면 [+1, -1]로 0이 되므로 확실히 갈린다.
        val cat = givenCat(evaluatedDaysAgo = 6)
        givenMeals(
            meal(daysAgo = 5, calory = 4000), // 첫 구간 [6일 전, 3일 전)
            meal(daysAgo = 2, calory = 4000), // 둘째 구간 [3일 전, 오늘)
        )

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-2)
    }

    @Test
    @DisplayName("체형 한계는 구간마다 적용되어, 초과분이 다음 구간의 굶주림을 상쇄하지 않는다")
    fun clampsEachIntervalSeparately() {
        // given: 뚱냥이(2)가 두 구간 과식하고 마지막 구간을 굶었다. steps = [+1, +1, -1]
        //        구간마다 clamp → 2, 2, 1 이므로 최종 1.
        //        합산 후 한 번만 clamp하면 +1이 되어 2로 남는다. 그 차이를 잡는다.
        val cat = givenCat(evaluatedDaysAgo = 9, weight = 2)
        givenMeals(
            meal(daysAgo = 8, calory = 8000), // 첫 구간 [9일 전, 6일 전)
            meal(daysAgo = 5, calory = 8000), // 둘째 구간 [6일 전, 3일 전)
        )

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(1)
    }

    @Test
    @DisplayName("구간 경계 자정에 먹은 식사는 뒤쪽 구간으로 집계된다")
    fun mealOnIntervalBoundaryBelongsToLaterInterval() {
        // given: 6일 전 평가(2구간), 경계는 3일 전 자정. 그 순간에 과식 기록이 하나 있다.
        //        뒤쪽 구간이면 steps = [-1, +1] → 2, 1, 2 이므로 최종 2.
        //        앞쪽 구간으로 새면 [+1, -1] → 2(clamp), 1 이므로 1이 되어 갈린다.
        val cat = givenCat(evaluatedDaysAgo = 6, weight = 2)
        givenMeals(mealAtMidnight(daysAgo = 3, calory = 20000))

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(2)
    }

    // ---------------------------------------------------------------- 평가 창 상한

    @Test
    @DisplayName("오래 비운 사용자는 최근 네 구간만 평가하고 그 앞 구간은 버린다")
    fun limitsEvaluationWindow() {
        // given: 30일 전 평가 → 지난 구간 10개지만 네 구간(12일)만 본다.
        //        창 밖인 20일 전의 과식 기록은 쿼리 범위에서 걸러져 영향을 주지 않는다.
        val cat = givenCat(evaluatedDaysAgo = 30)
        givenMeals(meal(daysAgo = 20, calory = 60000))

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-2)
        // 창을 줄여도 평가 경계는 지난 구간 전체의 끝(= 오늘)으로 전진한다.
        assertThat(cat.weightUpdatedAt).isEqualTo(midnight(0))
        verify { mealRepository.getMealsByUserIdAndPeriod(userId, midnight(12), midnight(0)) }
    }

    @Test
    @DisplayName("구간을 채우지 못한 남은 일수는 다음 평가로 넘어간다")
    fun carriesOverRemainingDays() {
        // given: 5일 전 평가 → 지난 구간 1개, 남은 2일은 평가하지 않는다.
        //        평가 시각이 오늘이 아니라 2일 전으로 전진해야 경계가 밀리지 않는다.
        val cat = givenCat(evaluatedDaysAgo = 5)

        // when
        catService.getCat(userId)

        // then
        assertThat(cat.weight).isEqualTo(-1)
        assertThat(cat.weightUpdatedAt).isEqualTo(midnight(2))
        verify { mealRepository.getMealsByUserIdAndPeriod(userId, midnight(5), midnight(2)) }
    }

    // ---------------------------------------------------------------- 진입점

    @Test
    @DisplayName("고양이가 없으면 CAT_NOT_FOUND를 던진다")
    fun catNotFound() {
        // given
        every { catRepository.findByUserId(userId) } returns null

        // when & then
        val exception = assertThrows<BusinessException> { catService.getCat(userId) }

        assertThat(exception.errorCode).isEqualTo(CatErrorCode.CAT_NOT_FOUND)
    }

    @Test
    @DisplayName("고양이 조회는 갱신된 체형을 응답에 담는다")
    fun getCatReturnsUpdatedWeight() {
        // given: 3일 굶어 보통냥(0) → 날씬냥(-1)
        givenCat(evaluatedDaysAgo = 3)

        // when
        val response = catService.getCat(userId)

        // then
        assertThat(response.weight).isEqualTo(CatWeight.SLIM.name)
        assertThat(response.weightName).isEqualTo(CatWeight.SLIM.description)
    }

    @Test
    @DisplayName("애니메이션 조회도 체형을 갱신하고, 갱신된 체형의 메타데이터를 읽는다")
    fun getCatAnimationsUpdatesWeight() {
        // given: 이 경로가 readOnly 트랜잭션이면 갱신이 flush 없이 사라진다.
        val cat = givenCat(evaluatedDaysAgo = 3)
        val animations = CatAnimationResponse(
            weight = CatWeight.SLIM.name,
            baseUrl = "https://example.com/",
            animations = emptyList(),
        )
        every { catAnimationLoader.load(CatWeight.SLIM) } returns animations

        // when
        val response = catService.getCatAnimations(userId)

        // then
        assertThat(cat.weight).isEqualTo(-1)
        assertThat(response).isEqualTo(animations)
        verify { catAnimationLoader.load(CatWeight.SLIM) }
    }

    // ---------------------------------------------------------------- 픽스처

    /** [daysAgo]일 전 자정. 평가 구간의 경계는 모두 자정이다. */
    private fun midnight(daysAgo: Long): Instant = today.minusDays(daysAgo).atStartOfDay(zone).toInstant()

    /**
     * 마지막 평가가 [evaluatedDaysAgo]일 전 자정이었던 고양이를 등록한다.
     *
     * weight와 weightUpdatedAt은 [Cat.updateWeightByStep]로만 바꿀 수 있으므로,
     * 초기 체형은 0에서의 변화량으로 지정한다.
     */
    private fun givenCat(evaluatedDaysAgo: Long, weight: Int = 0): Cat {
        val cat = Cat(name = "나비", userId = userId)
            .apply { updateWeightByStep(weight, midnight(evaluatedDaysAgo)) }

        every { catRepository.findByUserId(userId) } returns cat

        return cat
    }

    /**
     * 실제 JPQL(`mealAt >= :start AND mealAt < :end`)과 같은 반개구간으로 걸러 돌려준다.
     *
     * `returns`로 전부 돌려주면 서비스가 잡은 평가 창이 틀려도 테스트가 통과하고,
     * 창 밖 식사가 구간 배열의 범위를 벗어나 터지는 것도 감춰진다.
     */
    private fun givenMeals(vararg meals: Meal) {
        every { mealRepository.getMealsByUserIdAndPeriod(userId, any(), any()) } answers {
            val start = secondArg<Instant>()
            val end = thirdArg<Instant>()

            meals.filter { it.mealAt >= start && it.mealAt < end }
        }
    }

    /** [daysAgo]일 전 정오에 먹은 식사. 구간 경계(자정)와 헷갈리지 않게 정오로 둔다. */
    private fun meal(daysAgo: Long, calory: Int?, status: MealStatus = MealStatus.COMPLETED): Meal =
        createMeal(mealAt = today.minusDays(daysAgo).atTime(12, 0).atZone(zone).toInstant(), calory, status)

    /** [daysAgo]일 전 자정 정각에 먹은 식사. 구간 경계가 어느 쪽에 속하는지 확인할 때 쓴다. */
    private fun mealAtMidnight(daysAgo: Long, calory: Int?): Meal =
        createMeal(mealAt = midnight(daysAgo), calory, MealStatus.COMPLETED)

    private fun createMeal(mealAt: Instant, calory: Int?, status: MealStatus): Meal = Meal(
        status = status,
        imageKey = "test-image.jpg",
        mealAt = mealAt,
        userId = userId,
    ).apply { this.calory = calory }
}

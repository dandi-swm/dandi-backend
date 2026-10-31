package com.dandi.nyummy.cat.service

import com.dandi.nyummy.cat.config.CatProperties
import com.dandi.nyummy.cat.dto.CatAnimationResponse
import com.dandi.nyummy.cat.dto.CreateCatRequest
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.enum.CatWeight
import com.dandi.nyummy.cat.repository.CatAnimationLoader
import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CatErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.repository.ProfileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 프로필을 null로 고정해 목표 섭취량을 기본 권장량(2000 * 1일 = 2000)으로 단순화한다.
 * 따라서 하루의 증가 임계는 2400(120%)이다.
 * 프로필 기반 권장량 계산 자체는 NutritionCalculator의 책임이다.
 *
 * 평가 창은 `weightUpdatedAt`을 기준으로 잡히므로, 테스트는 "며칠 전에 평가됐는지"로 상태를
 * 지정하고 갱신 후 `weightUpdatedAt`이 어느 날의 0시로 전진했는지까지 확인한다.
 */
class CatServiceTest {

    private val catRepository: CatRepository = mockk()
    private val mealRepository: MealRepository = mockk()
    private val profileRepository: ProfileRepository = mockk()
    private val catAnimationLoader: CatAnimationLoader = mockk()
    private val catProperties = CatProperties(
        weightUpdateIntervalDays = 1,
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
        every { profileRepository.findProfileByUserId(userId) } returns null
        givenMeals()
    }

    // ---------------------------------------------------------------- 평가하지 않는 경우

    @Test
    fun `오늘 이미 평가했으면 식사를 조회하지 않고 체형과 평가 시각을 그대로 둔다`() {
        // given
        val cat = givenCat(evaluatedDaysAgo = 0)

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
        assertEquals(midnight(0), cat.weightUpdatedAt)
        verify(exactly = 0) { mealRepository.findMealsByUserIdAndPeriod(any(), any(), any()) }
    }

    @Test
    fun `평가 시각이 미래면 평가하지 않는다`() {
        // given: 시계 역전이나 timezone 변경으로 생길 수 있다.
        //        지난 구간이 음수가 되므로 구간 배열 크기가 음수가 되어 터지지 않아야 한다.
        val cat = givenCat(evaluatedDaysAgo = -1)

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
        assertEquals(midnight(-1), cat.weightUpdatedAt)
        verify(exactly = 0) { mealRepository.findMealsByUserIdAndPeriod(any(), any(), any()) }
    }

    // ---------------------------------------------------------------- 하루

    @Test
    fun `어제 과식했으면 체형이 한 단계 올라가고 평가 시각이 오늘 0시로 전진한다`() {
        // given: 어제 평가 → 평가 창은 [어제, 오늘)
        val cat = givenCat(evaluatedDaysAgo = 1)
        givenMeals(meal(daysAgo = 1, calory = 3000)) // 증가 임계 2400 초과

        // when
        catService.getCat(userId)

        // then
        assertEquals(1, cat.weight)
        assertEquals(midnight(0), cat.weightUpdatedAt)
        verify { mealRepository.findMealsByUserIdAndPeriod(userId, midnight(1), midnight(0)) }
    }

    @Test
    fun `어제 기록이 없으면 체형이 한 단계 내려간다`() {
        // given
        val cat = givenCat(evaluatedDaysAgo = 1)

        // when
        catService.getCat(userId)

        // then
        assertEquals(-1, cat.weight)
        assertEquals(midnight(0), cat.weightUpdatedAt)
    }

    @Test
    fun `적게 먹은 날은 빠지지 않고 보통냥 쪽으로 한 칸 움직인다`() {
        // given: 날씬냥(-1)이 목표의 25%만 먹었다. 소식을 -1로 보면 홀쭉냥(-2)이 된다.
        val cat = givenCat(evaluatedDaysAgo = 1, weight = -1)
        givenMeals(meal(daysAgo = 1, calory = 500))

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
    }

    @Test
    fun `분석에 실패한 식사만 있는 날은 굶은 것으로 보지 않고 보통냥 쪽으로 한 칸 움직인다`() {
        // given: 날씬냥(-1). 기록 없음으로 보면 -2, 유지면 -1, 보통냥 쪽이면 0이라 셋이 갈린다.
        val cat = givenCat(evaluatedDaysAgo = 1, weight = -1)
        givenMeals(meal(daysAgo = 1, calory = null, status = MealStatus.FAILED))

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
    }

    @Test
    fun `분석에 실패한 식사의 칼로리는 섭취량에 넣지 않는다`() {
        // given: COMPLETED 2000만 집계하면 적정이지만,
        //        FAILED 1000까지 더해지면 3000이 되어 증가 임계를 넘는다.
        val cat = givenCat(evaluatedDaysAgo = 1)
        givenMeals(
            meal(daysAgo = 1, calory = 2000, status = MealStatus.COMPLETED),
            meal(daysAgo = 1, calory = 1000, status = MealStatus.FAILED),
        )

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
    }

    @Test
    fun `칼로리가 비어 있는 완료 식사는 섭취량 0이지만 기록으로는 센다`() {
        // given: 상태는 COMPLETED지만 calory가 null인 경우 (분석 결과 누락)
        //        기록 없음으로 보면 -1이 된다.
        val cat = givenCat(evaluatedDaysAgo = 1)
        givenMeals(meal(daysAgo = 1, calory = null))

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
    }

    @Test
    fun `이미 최대 체형이면 과식해도 더 증가하지 않는다`() {
        // given: 뚱냥이(2) 상태에서 목표의 120%를 초과해 섭취
        val cat = givenCat(evaluatedDaysAgo = 1, weight = 2)
        givenMeals(meal(daysAgo = 1, calory = 10000))

        // when
        catService.getCat(userId)

        // then
        assertEquals(2, cat.weight)
    }

    @Test
    fun `이미 최소 체형이면 굶어도 더 감소하지 않는다`() {
        // given: 홀쭉냥(-2) 상태에서 기록 없음
        val cat = givenCat(evaluatedDaysAgo = 1, weight = -2)

        // when
        catService.getCat(userId)

        // then
        assertEquals(-2, cat.weight)
    }

    // ---------------------------------------------------------------- 적정 섭취 수렴

    @Test
    fun `뚱냥이가 적정 섭취하면 보통냥 쪽으로 한 칸만 내려간다`() {
        // given: 뚱냥이(2)가 하루 동안 목표(2000)만큼 먹었다. 유지가 아니라 한 칸 내려가야 한다.
        val cat = givenCat(evaluatedDaysAgo = 1, weight = 2)
        givenMeals(meal(daysAgo = 1, calory = 2000))

        // when
        catService.getCat(userId)

        // then
        assertEquals(1, cat.weight)
    }

    @Test
    fun `홀쭉냥이 적정 섭취하면 보통냥 쪽으로 한 칸 올라간다`() {
        // given
        val cat = givenCat(evaluatedDaysAgo = 1, weight = -2)
        givenMeals(meal(daysAgo = 1, calory = 2000))

        // when
        catService.getCat(userId)

        // then
        assertEquals(-1, cat.weight)
    }

    @Test
    fun `보통냥이 적정 섭취하면 체형이 유지된다`() {
        // given: 수렴의 종착점이므로 더 움직이지 않는다.
        val cat = givenCat(evaluatedDaysAgo = 1, weight = 0)
        givenMeals(meal(daysAgo = 1, calory = 2000))

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
    }

    @Test
    fun `뚱냥이가 사흘 내내 적정 섭취하면 한 칸씩 내려와 보통냥에서 멈춘다`() {
        // given: steps = [-1, -1, 0] → 1, 0, 0 이므로 최종 0.
        //        현재 체형을 루프 밖에서 한 번만 읽으면 [-1, -1, -1]이 되어 -1까지 지나간다.
        val cat = givenCat(evaluatedDaysAgo = 3, weight = 2)
        givenMeals(
            meal(daysAgo = 3, calory = 2000),
            meal(daysAgo = 2, calory = 2000),
            meal(daysAgo = 1, calory = 2000),
        )

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
    }

    // ---------------------------------------------------------------- 여러 날 누적

    @Test
    fun `이틀 굶으면 두 단계 내려간다`() {
        // given: 2일 전 평가 → 평가 창은 [2일 전, 오늘)
        val cat = givenCat(evaluatedDaysAgo = 2)

        // when
        catService.getCat(userId)

        // then
        assertEquals(-2, cat.weight)
        assertEquals(midnight(0), cat.weightUpdatedAt)
        verify { mealRepository.findMealsByUserIdAndPeriod(userId, midnight(2), midnight(0)) }
    }

    @Test
    fun `사흘 굶으면 사흘 모두 적용되지만 최소 체형에서 멈춘다`() {
        // given: 보통냥(0)에서 시작하면 이틀 만에 바닥에 닿는다.
        val cat = givenCat(evaluatedDaysAgo = 3)

        // when
        catService.getCat(userId)

        // then
        assertEquals(-2, cat.weight)
    }

    @Test
    fun `뚱냥이가 나흘 굶으면 나흘 모두 적용되어 홀쭉냥까지 내려간다`() {
        // given: 체형 폭(4단계)을 전부 쓰는 유일한 경우
        val cat = givenCat(evaluatedDaysAgo = 4, weight = 2)

        // when
        catService.getCat(userId)

        // then
        assertEquals(-2, cat.weight)
        assertEquals(midnight(0), cat.weightUpdatedAt)
    }

    @Test
    fun `섭취량은 날짜별로 나눠 평가한다`() {
        // given: 홀쭉냥(-2)이 이틀 동안 1300씩 먹었다. 날짜별로 보면 둘 다 소식이라
        //        보통냥 쪽으로 두 칸 → 0이 된다.
        //        한쪽 날에 2600이 몰리면 [+1, -1]이나 [-1, +1]이 되어 -2나 -1에서 끝나므로 갈린다.
        val cat = givenCat(evaluatedDaysAgo = 2, weight = -2)
        givenMeals(
            meal(daysAgo = 2, calory = 1300),
            meal(daysAgo = 1, calory = 1300),
        )

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
    }

    @Test
    fun `체형 한계는 날마다 적용되어, 초과분이 다음 날의 굶주림을 상쇄하지 않는다`() {
        // given: 뚱냥이(2)가 이틀 과식하고 어제 굶었다. steps = [+1, +1, -1]
        //        날마다 clamp → 2, 2, 1 이므로 최종 1.
        //        합산 후 한 번만 clamp하면 +1이 되어 2로 남는다. 그 차이를 잡는다.
        val cat = givenCat(evaluatedDaysAgo = 3, weight = 2)
        givenMeals(
            meal(daysAgo = 3, calory = 3000),
            meal(daysAgo = 2, calory = 3000),
        )

        // when
        catService.getCat(userId)

        // then
        assertEquals(1, cat.weight)
    }

    @Test
    fun `자정에 먹은 식사는 그날로 집계된다`() {
        // given: 2일 전 평가, 경계는 어제 0시. 그 순간에 과식 기록이 하나 있다.
        //        어제로 집계되면 steps = [-1, +1] → 1, 2 이므로 최종 2.
        //        그제로 새면 [+1, -1] → 2(clamp), 1 이므로 1이 되어 갈린다.
        val cat = givenCat(evaluatedDaysAgo = 2, weight = 2)
        givenMeals(mealAtMidnight(daysAgo = 1, calory = 10000))

        // when
        catService.getCat(userId)

        // then
        assertEquals(2, cat.weight)
    }

    // ---------------------------------------------------------------- 분석 중인 식사

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MealStatus::class, names = ["WAITING", "ANALYZING"])
    fun `어제 분석이 끝나지 않은 식사가 있으면 어제는 평가하지 않고 평가 시각을 어제 0시에 둔다`(status: MealStatus) {
        // given: 자정 직전에 시작한 분석이 끝나기 전에 조회한 경우
        val cat = givenCat(evaluatedDaysAgo = 1)
        givenMeals(meal(daysAgo = 1, calory = null, status = status))

        // when
        catService.getCat(userId)

        // then
        assertEquals(0, cat.weight)
        assertEquals(midnight(1), cat.weightUpdatedAt)
    }

    @Test
    fun `어제 분석이 끝난 뒤 다시 조회하면 어제를 평가한다`() {
        // given: 첫 조회는 분석 중이라 미뤄진다.
        val cat = givenCat(evaluatedDaysAgo = 1)
        val meal = meal(daysAgo = 1, calory = null, status = MealStatus.ANALYZING)
        givenMeals(meal)
        catService.getCat(userId)

        meal.status = MealStatus.COMPLETED
        meal.calory = 3000

        // when
        catService.getCat(userId)

        // then
        assertEquals(1, cat.weight)
        assertEquals(midnight(0), cat.weightUpdatedAt)
    }

    @Test
    fun `어제 평가를 미뤄도 그 앞 날들은 평가한다`() {
        // given: 그제와 그끄제는 기록이 없고, 어제는 분석 중이다.
        val cat = givenCat(evaluatedDaysAgo = 3)
        givenMeals(meal(daysAgo = 1, calory = null, status = MealStatus.ANALYZING))

        // when
        catService.getCat(userId)

        // then
        assertEquals(-2, cat.weight)
        assertEquals(midnight(1), cat.weightUpdatedAt)
    }

    @Test
    fun `분석 중인 식사가 그제 것이면 미루지 않고 기록이 있는 날로 평가한다`() {
        // given: ANALYZING에 멈춘 식사가 평가를 계속 막지 않는지 확인한다.
        //        날씬냥(-1) → 그제: 기록 있음, 보통냥 쪽으로 0 → 어제: 기록 없음 -1.
        //        그제까지 미루면 평가 시각이 2일 전에 남고, 기록 없음으로 보면 -2가 된다.
        val cat = givenCat(evaluatedDaysAgo = 2, weight = -1)
        givenMeals(meal(daysAgo = 2, calory = null, status = MealStatus.ANALYZING))

        // when
        catService.getCat(userId)

        // then
        assertEquals(-1, cat.weight)
        assertEquals(midnight(0), cat.weightUpdatedAt)
    }

    // ---------------------------------------------------------------- 평가 창 상한

    @Test
    fun `오래 비운 사용자는 최근 나흘만 평가하고 그 앞 날은 버린다`() {
        // given: 30일 전 평가 → 지난 구간 30개지만 나흘만 본다.
        //        창 밖인 20일 전의 과식 기록은 쿼리 범위에서 걸러져 영향을 주지 않는다.
        val cat = givenCat(evaluatedDaysAgo = 30)
        givenMeals(meal(daysAgo = 20, calory = 60000))

        // when
        catService.getCat(userId)

        // then
        assertEquals(-2, cat.weight)
        // 창을 줄여도 평가 경계는 지난 구간 전체의 끝(= 오늘)으로 전진한다.
        assertEquals(midnight(0), cat.weightUpdatedAt)
        verify { mealRepository.findMealsByUserIdAndPeriod(userId, midnight(4), midnight(0)) }
    }

    // ---------------------------------------------------------------- 진입점

    @Test
    fun `고양이가 없으면 CAT_NOT_FOUND를 던진다`() {
        // given
        every { catRepository.findByUserId(userId) } returns null

        // when & then
        assertBusinessException(CatErrorCode.CAT_NOT_FOUND) { catService.getCat(userId) }
    }

    @Test
    fun `고양이 조회는 갱신된 체형을 응답에 담는다`() {
        // given: 어제 굶어 보통냥(0) → 날씬냥(-1)
        givenCat(evaluatedDaysAgo = 1)

        // when
        val response = catService.getCat(userId)

        // then
        assertEquals(CatWeight.SLIM, response.weight)
        assertEquals(CatWeight.SLIM.description, response.weightName)
    }

    @Test
    fun `애니메이션 조회도 체형을 갱신하고, 갱신된 체형의 메타데이터를 읽는다`() {
        // given: 이 경로가 readOnly 트랜잭션이면 갱신이 flush 없이 사라진다.
        val cat = givenCat(evaluatedDaysAgo = 1)
        val animations = CatAnimationResponse(
            weight = CatWeight.SLIM.name,
            baseUrl = "https://example.com/",
            animations = emptyList(),
        )
        every { catAnimationLoader.loadCatAnimation(CatWeight.SLIM) } returns animations

        // when
        val response = catService.getCatAnimations(userId)

        // then
        assertEquals(-1, cat.weight)
        assertEquals(animations, response)
        verify { catAnimationLoader.loadCatAnimation(CatWeight.SLIM) }
    }

    // ---------------------------------------------------------------- 고양이 생성

    @Test
    fun `이름이 8자를 넘으면 고양이를 만들지 않고 거부한다`() {
        // when & then
        assertBusinessException(CatErrorCode.NAME_TOO_LONG) {
            catService.createCat(userId, CreateCatRequest(name = "가나다라마바사아자"))
        }
        verify(exactly = 0) { catRepository.save(any()) }
    }

    @Test
    fun `이름이 8자면 고양이를 만든다`() {
        // given
        val saved = slot<Cat>()
        every { catRepository.existsByUserId(userId) } returns false
        every { profileRepository.findProfileByUserId(userId) } returns Profile(userId = userId)
        every { catRepository.save(capture(saved)) } answers { firstArg() }

        // when
        catService.createCat(userId, CreateCatRequest(name = "가나다라마바사아"))

        // then
        assertEquals("가나다라마바사아", saved.captured.name)
    }

    // ---------------------------------------------------------------- 픽스처

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

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
        every { mealRepository.findMealsByUserIdAndPeriod(userId, any(), any()) } answers {
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

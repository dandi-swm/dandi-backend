package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisJob
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.meal.service.MealFoodService
import com.dandi.nyummy.worker.config.WorkerJobProperties
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.AnalysisMealFoodResult
import com.dandi.nyummy.worker.dto.AnalysisMealResult
import com.dandi.nyummy.worker.dto.AnalysisPushResult
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * leaseMealAnalysisJobs가 잠가 가져온 작업에 lease를 걸고 식사를 ANALYZING으로 바꾸는지, 지워진 식사와 최대 시도를 채운 작업을 건너뛰는지,
 * 확정·재시도·되돌리기·실패가 작업이 아직 그 번호일 때만 결과를 쓰는지 다룬다.
 * 인식 작업의 식사는 가져갈 때도 되돌릴 때도 RECOGNIZING 그대로 두는지도 다룬다.
 * SKIP LOCKED로 두 Worker가 겹치지 않는지는 실제 DB가 필요해 여기서 다루지 않는다.
 *
 * lease 테스트의 작업·식사는 id와 lease_count가 생성자 밖의 val이라 mockk로 만든다.
 * 확정·실패 테스트의 식사는 필드가 실제로 바뀌는지 봐야 해서 실제 엔티티를 쓴다.
 */
class MealAnalysisJobTransactionServiceTest {

    private val now = Instant.parse("2026-11-02T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val jobCreatedAt = now.minusSeconds(30)
    private val limit = 50

    private val mealAnalysisJobRepository = mockk<MealAnalysisJobRepository>()
    private val mealRepository = mockk<MealRepository>()
    private val mealFoodService = mockk<MealFoodService>()
    private val catRepository = mockk<CatRepository>()
    private val catService = mockk<CatService>()

    private val mealAnalysisJobTransactionService = MealAnalysisJobTransactionService(
        mealAnalysisJobRepository = mealAnalysisJobRepository,
        mealRepository = mealRepository,
        mealFoodService = mealFoodService,
        catRepository = catRepository,
        catService = catService,
        workerProperties = WorkerProperties(
            enabled = true,
            analysis = WorkerJobProperties(leaseTimeToLive = Duration.ofSeconds(60)),
        ),
        clock = clock,
    )

    init {
        every { mealAnalysisJobRepository.updateLease(any(), any()) } answers { firstArg<Collection<Long>>().size }
        every { mealAnalysisJobRepository.deleteAllByIdInBatch(any()) } just Runs
        every { mealRepository.updateMealStatuses(any(), any(), any()) } answers { firstArg<Collection<Long>>().size }
    }

    private fun createJob(
        id: Long,
        mealId: Long,
        leaseCount: Int = 0,
        language: Language? = null,
        category: MealAnalysisJobCategory? = null,
        availableAt: Instant? = null,
    ): MealAnalysisJob = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.mealId } returns mealId
        every { this@mockk.leaseCount } returns leaseCount
        every { this@mockk.createdAt } returns jobCreatedAt
        every { this@mockk.availableAt } returns availableAt
        every { this@mockk.language } returns language
        every { this@mockk.category } returns category
    }

    private fun createMeal(id: Long, deletedAt: Instant? = null): Meal = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.deletedAt } returns deletedAt
        every { this@mockk.imageKey } returns "meals/1/2026/11/02/meal-$id.jpg"
    }

    private fun givenJobs(vararg jobs: MealAnalysisJob) {
        every { mealAnalysisJobRepository.findMealAnalysisJobsWithLock(limit) } returns jobs.toList()
    }

    private fun givenMeals(vararg meals: Meal) {
        every { mealRepository.findAllById(any<Iterable<Long>>()) } returns meals.toList()
    }

    @Test
    fun `가져갈 작업이 없으면 빈 목록을 돌려주고 아무것도 바꾸지 않는다`() {
        givenJobs()

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        assertTrue(results.isEmpty())
        verify(exactly = 0) { mealAnalysisJobRepository.updateLease(any(), any()) }
        verify(exactly = 0) { mealRepository.updateMealStatuses(any(), any(), any()) }
        verify(exactly = 0) { mealAnalysisJobRepository.deleteAllByIdInBatch(any()) }
    }

    @Test
    fun `받은 개수를 그대로 잠금 조회에 넘긴다`() {
        givenJobs()

        mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        verify(exactly = 1) { mealAnalysisJobRepository.findMealAnalysisJobsWithLock(limit) }
    }

    @Test
    fun `가져간 작업의 lease를 설정한 길이로 잡는다`() {
        givenJobs(createJob(id = 1L, mealId = 10L), createJob(id = 2L, mealId = 20L))
        givenMeals(createMeal(id = 10L), createMeal(id = 20L))

        mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        verify(exactly = 1) { mealAnalysisJobRepository.updateLease(listOf(1L, 2L), 60L) }
    }

    @Test
    fun `가져간 작업의 식사를 ANALYZING으로 바꾼다`() {
        givenJobs(createJob(id = 1L, mealId = 10L), createJob(id = 2L, mealId = 20L))
        givenMeals(createMeal(id = 10L), createMeal(id = 20L))

        mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        verify(exactly = 1) { mealRepository.updateMealStatuses(listOf(10L, 20L), MealStatus.ANALYZING, now) }
    }

    @Test
    fun `인식 작업의 식사는 RECOGNIZING 그대로 두고 그 밖의 식사만 ANALYZING으로 바꾼다`() {
        givenJobs(
            createJob(id = 1L, mealId = 10L, category = MealAnalysisJobCategory.RECOGNITION),
            createJob(id = 2L, mealId = 20L, category = MealAnalysisJobCategory.NUTRITION),
            createJob(id = 3L, mealId = 30L, category = null),
        )
        givenMeals(createMeal(id = 10L), createMeal(id = 20L), createMeal(id = 30L))

        mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        // lease는 종류와 상관없이 모두 건다.
        verify(exactly = 1) { mealAnalysisJobRepository.updateLease(listOf(1L, 2L, 3L), 60L) }
        verify(exactly = 1) { mealRepository.updateMealStatuses(listOf(20L, 30L), MealStatus.ANALYZING, now) }
    }

    @Test
    fun `인식 작업만 가져가면 식사 상태를 바꾸지 않는다`() {
        givenJobs(createJob(id = 1L, mealId = 10L, category = MealAnalysisJobCategory.RECOGNITION))
        givenMeals(createMeal(id = 10L))

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        assertEquals(listOf(1L), results.map { it.jobId })
        verify(exactly = 0) { mealRepository.updateMealStatuses(any(), any(), any()) }
    }

    @Test
    fun `작업 종류를 결과에 담고 종류가 없는 작업은 v1 분석 작업으로 둔다`() {
        givenJobs(
            createJob(id = 1L, mealId = 10L, category = MealAnalysisJobCategory.RECOGNITION),
            createJob(id = 2L, mealId = 20L, category = MealAnalysisJobCategory.NUTRITION),
            createJob(id = 3L, mealId = 30L, category = null),
        )
        givenMeals(createMeal(id = 10L), createMeal(id = 20L), createMeal(id = 30L))

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        assertEquals(
            listOf(MealAnalysisJobCategory.RECOGNITION, MealAnalysisJobCategory.NUTRITION, null),
            results.map { it.category },
        )
    }

    @Test
    fun `미뤘던 작업은 가져갈 수 있게 된 시각을, 미루지 않은 작업은 쌓인 시각을 결과에 담는다`() {
        val delayedUntil = jobCreatedAt.plusSeconds(300)
        givenJobs(
            createJob(id = 1L, mealId = 10L, availableAt = delayedUntil),
            createJob(id = 2L, mealId = 20L, availableAt = null),
        )
        givenMeals(createMeal(id = 10L), createMeal(id = 20L))

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        assertEquals(listOf(delayedUntil, jobCreatedAt), results.map { it.availableAt })
    }

    @Test
    fun `작업의 언어를 결과에 담고 언어를 몰랐던 작업은 원본 언어로 본다`() {
        givenJobs(
            createJob(id = 1L, mealId = 10L, language = Language.JA),
            createJob(id = 2L, mealId = 20L, language = null),
        )
        givenMeals(createMeal(id = 10L), createMeal(id = 20L))

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        assertEquals(listOf(Language.JA, Language.ORIGINAL_LANGUAGE), results.map { it.language })
    }

    @Test
    fun `돌려주는 번호는 읽은 lease_count에 1을 더한 값이고 식사의 이미지 키를 담는다`() {
        givenJobs(createJob(id = 1L, mealId = 10L, leaseCount = 0), createJob(id = 2L, mealId = 20L, leaseCount = 1))
        // 조회 순서가 작업 순서와 달라도 식사 id로 짝짓는지 본다.
        givenMeals(createMeal(id = 20L), createMeal(id = 10L))

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        assertEquals(
            listOf(
                MealAnalysisJobResult(
                    jobId = 1L,
                    mealId = 10L,
                    leaseCount = 1,
                    imageKey = "meals/1/2026/11/02/meal-10.jpg",
                    canRetry = true,
                    availableAt = jobCreatedAt,
                    language = Language.KO,
                ),
                MealAnalysisJobResult(
                    jobId = 2L,
                    mealId = 20L,
                    leaseCount = 2,
                    imageKey = "meals/1/2026/11/02/meal-20.jpg",
                    canRetry = false,
                    availableAt = jobCreatedAt,
                    language = Language.KO,
                ),
            ),
            results,
        )
    }

    @Test
    fun `지워진 식사의 작업은 지우고 lease와 상태 변경, 결과에서 뺀다`() {
        givenJobs(createJob(id = 1L, mealId = 10L), createJob(id = 2L, mealId = 20L))
        givenMeals(createMeal(id = 10L), createMeal(id = 20L, deletedAt = now.minusSeconds(5)))

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        verify(exactly = 1) { mealAnalysisJobRepository.deleteAllByIdInBatch(listOf(2L)) }
        verify(exactly = 1) { mealAnalysisJobRepository.updateLease(listOf(1L), 60L) }
        verify(exactly = 1) { mealRepository.updateMealStatuses(listOf(10L), MealStatus.ANALYZING, now) }
        assertEquals(listOf(1L), results.map { it.jobId })
    }

    @Test
    fun `모두 지워진 식사면 작업만 지우고 lease와 상태는 바꾸지 않는다`() {
        givenJobs(createJob(id = 1L, mealId = 10L))
        givenMeals(createMeal(id = 10L, deletedAt = now.minusSeconds(5)))

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        assertTrue(results.isEmpty())
        verify(exactly = 1) { mealAnalysisJobRepository.deleteAllByIdInBatch(listOf(1L)) }
        verify(exactly = 0) { mealAnalysisJobRepository.updateLease(any(), any()) }
        verify(exactly = 0) { mealRepository.updateMealStatuses(any(), any(), any()) }
    }

    @Test
    fun `최대 시도 횟수를 채운 만료 작업은 지우고 FAILED로 두며 lease와 결과에서 뺀다`() {
        givenJobs(createJob(id = 1L, mealId = 10L, leaseCount = 0), createJob(id = 2L, mealId = 20L, leaseCount = 2))
        givenMeals(createMeal(id = 10L), createMeal(id = 20L))

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        verify(exactly = 1) { mealAnalysisJobRepository.deleteAllByIdInBatch(listOf(2L)) }
        verify(exactly = 1) { mealRepository.updateMealStatuses(listOf(20L), MealStatus.FAILED, now) }
        verify(exactly = 1) { mealAnalysisJobRepository.updateLease(listOf(1L), 60L) }
        verify(exactly = 1) { mealRepository.updateMealStatuses(listOf(10L), MealStatus.ANALYZING, now) }
        assertEquals(listOf(1L), results.map { it.jobId })
    }

    @Test
    fun `최대 시도 횟수를 채운 만료 작업은 인식 작업이어도 FAILED로 둔다`() {
        givenJobs(
            createJob(id = 1L, mealId = 10L, leaseCount = 2, category = MealAnalysisJobCategory.RECOGNITION),
        )
        givenMeals(createMeal(id = 10L))

        val results = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(limit)

        assertTrue(results.isEmpty())
        verify(exactly = 1) { mealAnalysisJobRepository.deleteAllByIdInBatch(listOf(1L)) }
        verify(exactly = 1) { mealRepository.updateMealStatuses(listOf(10L), MealStatus.FAILED, now) }
    }

    // 확정·재시도·실패

    private val userId = 1L
    private val mealAt = now.minusSeconds(600)
    private val leasedJob =
        MealAnalysisJobResult(
            jobId = 1L,
            mealId = 10L,
            leaseCount = 2,
            imageKey = "meals/1/meal-10.jpg",
            canRetry = false,
            availableAt = jobCreatedAt,
            language = Language.KO,
        )
    private val analysisResult = NutritionAnalysisResult(
        name = "김치찌개",
        nutrition = Nutrition(calory = 450, carbs = 40, protein = 25, fat = 20),
        catComment = "단백질이 든든하다냥",
        iconId = 12L,
    )

    private fun createLockedMeal(deletedAt: Instant? = null) =
        Meal(status = MealStatus.ANALYZING, imageKey = leasedJob.imageKey, mealAt = mealAt, userId = userId).apply {
            if (deletedAt != null) updateDeletedAt(deletedAt)
        }

    /** 작업이 아직 이 Worker의 것이라 조건부 삭제가 1행을 지우고, 잠가 읽은 식사가 [meal]이다. */
    private fun givenOwnedJob(meal: Meal) {
        every { mealAnalysisJobRepository.deleteByIdAndLeaseCount(leasedJob.jobId, leasedJob.leaseCount) } returns 1
        every { mealRepository.findMealWithLockById(leasedJob.mealId) } returns meal
        every { catRepository.existsByUserId(userId) } returns true
        every { catService.updateLastMealAt(any(), any()) } just Runs
    }

    /** lease가 만료돼 다른 Worker가 같은 작업을 다음 번호로 가져가서 조건부 삭제가 0행이다. */
    private fun givenLostJob() {
        every { mealAnalysisJobRepository.deleteByIdAndLeaseCount(leasedJob.jobId, leasedJob.leaseCount) } returns 0
    }

    @Test
    fun `확정할 때 다른 Worker가 가져간 작업이면 아무것도 쓰지 않고 null을 돌려준다`() {
        givenLostJob()

        val result = mealAnalysisJobTransactionService.confirmMealAnalysis(leasedJob, analysisResult)

        assertNull(result)
        verify(exactly = 0) { mealRepository.findMealWithLockById(any()) }
        verify(exactly = 0) { catService.updateLastMealAt(any(), any()) }
    }

    @Test
    fun `작업은 가져갈 때 받은 번호로만 지운다`() {
        givenOwnedJob(createLockedMeal())

        mealAnalysisJobTransactionService.confirmMealAnalysis(leasedJob, analysisResult)

        verify(exactly = 1) { mealAnalysisJobRepository.deleteByIdAndLeaseCount(1L, 2) }
    }

    @Test
    fun `확정하면 결과를 저장하고 COMPLETED로 바꾸며 고양이의 마지막 식사 시각을 갱신한다`() {
        val meal = createLockedMeal()
        givenOwnedJob(meal)

        val result = mealAnalysisJobTransactionService.confirmMealAnalysis(leasedJob, analysisResult)

        assertEquals("김치찌개", meal.name)
        assertEquals(450, meal.calory)
        assertEquals(40, meal.carbs)
        assertEquals(25, meal.protein)
        assertEquals(20, meal.fat)
        assertEquals(12L, meal.iconId)
        assertEquals("단백질이 든든하다냥", meal.catComment)
        assertEquals(MealStatus.COMPLETED, meal.status)
        verify(exactly = 1) { catService.updateLastMealAt(userId, mealAt) }
        assertEquals(
            AnalysisPushResult(
                mealId = meal.id,
                userId = userId,
                mealName = "김치찌개",
                status = MealStatus.COMPLETED,
                isDeleted = false,
            ),
            result,
        )
    }

    @Test
    fun `고양이가 없으면 마지막 식사 시각만 건너뛰고 확정은 그대로 한다`() {
        val meal = createLockedMeal()
        givenOwnedJob(meal)
        every { catRepository.existsByUserId(userId) } returns false

        val result = mealAnalysisJobTransactionService.confirmMealAnalysis(leasedJob, analysisResult)

        assertEquals(MealStatus.COMPLETED, meal.status)
        assertNotNull(result)
        verify(exactly = 0) { catService.updateLastMealAt(any(), any()) }
    }

    @Test
    fun `분석하는 동안 식사가 지워졌으면 결과에 지워졌다고 표시한다`() {
        givenOwnedJob(createLockedMeal(deletedAt = now.minusSeconds(5)))

        val result = mealAnalysisJobTransactionService.confirmMealAnalysis(leasedJob, analysisResult)

        assertEquals(true, result?.isDeleted)
    }

    // confirmMealRecognition

    private val nutritionDelay = Duration.ofMinutes(5)
    private val recognitionJob = leasedJob.copy(category = MealAnalysisJobCategory.RECOGNITION, language = Language.JA)
    private val recognition = MealRecognitionResult(
        inputCategory = MealInputCategory.PACKAGE,
        name = "불고기 백반",
        foods = listOf(MealFoodRecognitionResult(name = "불고기", portion = BigDecimal("1.5"))),
        model = "gemini-recognition-test",
        promptVersion = "v1",
    )

    private fun createRecognizingMeal(deletedAt: Instant? = null) = Meal(
        status = MealStatus.RECOGNIZING,
        imageKey = recognitionJob.imageKey,
        mealAt = mealAt,
        userId = userId,
        analysisVersion = MealAnalysisVersion.V2,
    ).apply {
        if (deletedAt != null) updateDeletedAt(deletedAt)
    }

    /** 인식 작업이 아직 이 Worker의 것이고, 잠가 읽은 식사가 [meal]이다. */
    private fun givenOwnedRecognitionJob(meal: Meal) {
        givenOwnedJob(meal)
        every { mealFoodService.createMealFoods(any(), any(), any()) } returns emptyList()
        every { mealAnalysisJobRepository.save(any()) } answers { firstArg() }
        every { mealAnalysisJobRepository.updateAvailableAt(any(), any()) } returns 1
    }

    @Test
    fun `인식을 확정할 때 다른 Worker가 가져간 작업이면 아무것도 쓰지 않고 false를 돌려준다`() {
        givenLostJob()

        val isConfirmed =
            mealAnalysisJobTransactionService.confirmMealRecognition(recognitionJob, recognition, nutritionDelay)

        assertFalse(isConfirmed)
        verify(exactly = 0) { mealRepository.findMealWithLockById(any()) }
        verify(exactly = 0) { mealFoodService.createMealFoods(any(), any(), any()) }
        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
    }

    @Test
    fun `인식을 확정하면 대표 이름과 입력 유형을 저장하고 식사를 CONFIRMING으로 바꾼다`() {
        val meal = createRecognizingMeal()
        givenOwnedRecognitionJob(meal)

        val isConfirmed =
            mealAnalysisJobTransactionService.confirmMealRecognition(recognitionJob, recognition, nutritionDelay)

        assertTrue(isConfirmed)
        assertEquals("불고기 백반", meal.name)
        assertEquals(MealInputCategory.PACKAGE, meal.inputCategory)
        assertEquals(MealStatus.CONFIRMING, meal.status)
    }

    @Test
    fun `인식을 확정하면 Worker가 인식한 것으로 음식 줄을 저장한다`() {
        val meal = createRecognizingMeal()
        givenOwnedRecognitionJob(meal)

        mealAnalysisJobTransactionService.confirmMealRecognition(recognitionJob, recognition, nutritionDelay)

        verify(exactly = 1) { mealFoodService.createMealFoods(meal.id, recognition, MealRecognitionCategory.WORKER) }
    }

    @Test
    fun `인식을 확정하면 인식 작업을 지운 뒤 같은 언어의 영양 작업을 만들고 지연 시간 뒤부터 가져가게 미룬다`() {
        val meal = createRecognizingMeal()
        givenOwnedRecognitionJob(meal)

        mealAnalysisJobTransactionService.confirmMealRecognition(recognitionJob, recognition, nutritionDelay)

        // 식사 하나에 작업은 하나라(유니크 제약) 인식 작업을 먼저 지워야 영양 작업을 만들 수 있다.
        verifyOrder {
            mealAnalysisJobRepository.deleteByIdAndLeaseCount(recognitionJob.jobId, recognitionJob.leaseCount)
            mealAnalysisJobRepository.save(
                match {
                    it.mealId == meal.id &&
                        it.category == MealAnalysisJobCategory.NUTRITION &&
                        it.language == Language.JA
                },
            )
            mealAnalysisJobRepository.updateAvailableAt(any(), 300L)
        }
    }

    @Test
    fun `지연 시간이 0이면 영양 작업을 미루지 않는다`() {
        givenOwnedRecognitionJob(createRecognizingMeal())

        mealAnalysisJobTransactionService.confirmMealRecognition(recognitionJob, recognition, Duration.ZERO)

        verify(exactly = 1) { mealAnalysisJobRepository.save(any()) }
        verify(exactly = 0) { mealAnalysisJobRepository.updateAvailableAt(any(), any()) }
    }

    @Test
    fun `인식하는 동안 식사가 지워졌으면 인식 작업만 지우고 음식 줄과 영양 작업을 만들지 않는다`() {
        val meal = createRecognizingMeal(deletedAt = now.minusSeconds(5))
        givenOwnedRecognitionJob(meal)

        val isConfirmed =
            mealAnalysisJobTransactionService.confirmMealRecognition(recognitionJob, recognition, nutritionDelay)

        // 작업은 이 Worker가 끝냈으므로 true다.
        assertTrue(isConfirmed)
        assertEquals(MealStatus.RECOGNIZING, meal.status)
        assertEquals("", meal.name)
        verify(exactly = 1) {
            mealAnalysisJobRepository.deleteByIdAndLeaseCount(recognitionJob.jobId, recognitionJob.leaseCount)
        }
        verify(exactly = 0) { mealFoodService.createMealFoods(any(), any(), any()) }
        verify(exactly = 0) { mealAnalysisJobRepository.save(any()) }
    }

    // getAnalysisMeal·confirmMealNutrition

    private val nutritionJob = leasedJob.copy(category = MealAnalysisJobCategory.NUTRITION)
    private val bulgogiServingNutrition = createServingNutrition("350", "15.5", "25.25", "20")
    private val riceServingNutrition = createServingNutrition("300", "65", "6", "1")

    private fun createServingNutrition(calory: String, carbs: String, protein: String, fat: String) = ServingNutrition(
        calory = BigDecimal(calory),
        carbs = BigDecimal(carbs),
        protein = BigDecimal(protein),
        fat = BigDecimal(fat),
    )

    /** id가 생성자 밖의 val이라 mockk로 id만 흉내 내고, 나머지는 실제 엔티티 그대로 둔다. */
    private fun createMealFood(
        id: Long,
        name: String,
        portion: String,
        servingNutrition: ServingNutrition? = null,
        iconId: Long? = null,
    ): MealFood = spyk(
        MealFood(mealId = 0L, mealFoodSnapshotId = null, displayOrder = 1, name = name, portion = BigDecimal(portion)),
    ).apply {
        every { this@apply.id } returns id
        if (servingNutrition != null) updateServingNutrition(servingNutrition)
        if (iconId != null) updateIconId(iconId)
    }

    private fun createAnalyzingMeal(catComment: String? = null) = Meal(
        status = MealStatus.ANALYZING,
        imageKey = nutritionJob.imageKey,
        mealAt = mealAt,
        userId = userId,
        analysisVersion = MealAnalysisVersion.V2,
    ).apply {
        updateName("불고기 백반")
        updateInputCategory(MealInputCategory.PACKAGE)
        if (catComment != null) updateCatComment(catComment)
    }

    /** 영양 작업이 아직 이 Worker의 것이고, 1인분을 채운 뒤의 음식 줄이 [mealFoods]다. */
    private fun givenOwnedNutritionJob(meal: Meal, vararg mealFoods: MealFood) {
        givenOwnedJob(meal)
        every { mealFoodService.updateServingNutritions(any(), any()) } returns mealFoods.toList()
    }

    @Test
    fun `영양을 계산할 식사를 음식 줄과 함께 값으로 읽는다`() {
        every { mealRepository.findById(nutritionJob.mealId) } returns Optional.of(createAnalyzingMeal())
        every { mealFoodService.getMealFoods(any()) } returns listOf(
            createMealFood(101L, "불고기", "1.5"),
            createMealFood(102L, "잡곡밥", "1.0", riceServingNutrition, iconId = 5L),
        )

        val analysisMeal = mealAnalysisJobTransactionService.getAnalysisMeal(nutritionJob)

        assertEquals(
            AnalysisMealResult(
                userId = userId,
                mealAt = mealAt,
                inputCategory = MealInputCategory.PACKAGE,
                hasCatComment = false,
                mealFoods = listOf(
                    AnalysisMealFoodResult(101L, "불고기", BigDecimal("1.5"), null, null),
                    AnalysisMealFoodResult(102L, "잡곡밥", BigDecimal("1.0"), riceServingNutrition, 5L),
                ),
            ),
            analysisMeal,
        )
    }

    @Test
    fun `이미 코멘트가 있는 식사는 코멘트가 있다고 알린다`() {
        every { mealRepository.findById(nutritionJob.mealId) } returns
            Optional.of(createAnalyzingMeal(catComment = "든든하게 먹었다냥"))
        every { mealFoodService.getMealFoods(any()) } returns emptyList()

        assertEquals(true, mealAnalysisJobTransactionService.getAnalysisMeal(nutritionJob)?.hasCatComment)
    }

    @Test
    fun `영양을 계산할 식사 행이 없으면 null을 돌려준다`() {
        every { mealRepository.findById(nutritionJob.mealId) } returns Optional.empty()

        assertNull(mealAnalysisJobTransactionService.getAnalysisMeal(nutritionJob))
    }

    @Test
    fun `영양을 확정할 때 다른 Worker가 가져갔거나 작업이 바뀌었으면 아무것도 쓰지 않고 null을 돌려준다`() {
        givenLostJob()

        val result = mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), "든든하게 먹었다냥")

        assertNull(result)
        verify(exactly = 0) { mealRepository.findMealWithLockById(any()) }
        verify(exactly = 0) { mealFoodService.updateServingNutritions(any(), any()) }
    }

    @Test
    fun `영양을 확정하면 조회 결과를 음식 줄에 채우게 하고 줄을 더한 합계를 식사에 저장한다`() {
        val meal = createAnalyzingMeal()
        val servingNutritions = mapOf(101L to ServingNutritionResult(bulgogiServingNutrition, 12L))
        givenOwnedNutritionJob(
            meal,
            createMealFood(101L, "불고기", "1.5", bulgogiServingNutrition, iconId = 12L),
            createMealFood(102L, "잡곡밥", "1.0", riceServingNutrition, iconId = 5L),
        )

        mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, servingNutritions, null)

        verify(exactly = 1) { mealFoodService.updateServingNutritions(meal.id, servingNutritions) }
        // 350 × 1.5 + 300 = 825, 15.5 × 1.5 + 65 = 88.25 → 88, 25.25 × 1.5 + 6 = 43.875 → 44, 20 × 1.5 + 1 = 31
        assertEquals(825, meal.calory)
        assertEquals(88, meal.carbs)
        assertEquals(44, meal.protein)
        assertEquals(31, meal.fat)
    }

    @Test
    fun `영양을 확정하면 식사 아이콘은 맨 위 줄의 아이콘이고 대표 이름은 그대로 둔다`() {
        val meal = createAnalyzingMeal()
        givenOwnedNutritionJob(
            meal,
            createMealFood(101L, "불고기", "1.5", bulgogiServingNutrition, iconId = 12L),
            createMealFood(102L, "잡곡밥", "1.0", riceServingNutrition, iconId = 5L),
        )

        mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), null)

        assertEquals(12L, meal.iconId)
        assertEquals("불고기 백반", meal.name)
    }

    @Test
    fun `맨 위 줄에 아이콘이 없으면 식사 아이콘은 기본 아이콘이다`() {
        val meal = createAnalyzingMeal()
        givenOwnedNutritionJob(meal, createMealFood(101L, "불고기", "1.5", bulgogiServingNutrition, iconId = null))

        mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), null)

        assertEquals(5L, meal.iconId)
    }

    @Test
    fun `영양을 확정하면 COMPLETED로 바꾸고 고양이의 마지막 식사 시각을 갱신하며 알림 값을 돌려준다`() {
        val meal = createAnalyzingMeal()
        givenOwnedNutritionJob(meal, createMealFood(101L, "불고기", "1.5", bulgogiServingNutrition, iconId = 12L))

        val result = mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), null)

        assertEquals(MealStatus.COMPLETED, meal.status)
        verify(exactly = 1) { catService.updateLastMealAt(userId, mealAt) }
        assertEquals(
            AnalysisPushResult(
                mealId = meal.id,
                userId = userId,
                mealName = "불고기 백반",
                status = MealStatus.COMPLETED,
                isDeleted = false,
            ),
            result,
        )
    }

    @Test
    fun `코멘트가 없는 식사에는 새로 만든 코멘트를 저장한다`() {
        val meal = createAnalyzingMeal()
        givenOwnedNutritionJob(meal, createMealFood(101L, "불고기", "1.5", bulgogiServingNutrition, iconId = 12L))

        mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), "든든하게 먹었다냥")

        assertEquals("든든하게 먹었다냥", meal.catComment)
    }

    @Test
    fun `이미 코멘트가 있는 식사는 새 코멘트가 와도 처음의 코멘트를 그대로 둔다`() {
        // 코멘트를 만드는 사이 다른 계산이 먼저 끝나 코멘트를 저장한 경우
        val meal = createAnalyzingMeal(catComment = "처음 코멘트다냥")
        givenOwnedNutritionJob(meal, createMealFood(101L, "불고기", "1.5", bulgogiServingNutrition, iconId = 12L))

        mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), "나중 코멘트다냥")

        assertEquals("처음 코멘트다냥", meal.catComment)
    }

    @Test
    fun `1인분 값이 없는 줄이 남아 있으면 예외를 던져 확정하지 않는다`() {
        // 예외가 밖으로 나가야 @Transactional이 작업 삭제까지 롤백한다.
        val meal = createAnalyzingMeal()
        givenOwnedNutritionJob(meal, createMealFood(101L, "불고기", "1.5", servingNutrition = null))

        assertFailsWith<IllegalStateException> {
            mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), null)
        }
        assertEquals(MealStatus.ANALYZING, meal.status)
    }

    @Test
    fun `음식 줄이 없으면 예외를 던져 확정하지 않는다`() {
        val meal = createAnalyzingMeal()
        givenOwnedNutritionJob(meal)

        assertFailsWith<IllegalStateException> {
            mealAnalysisJobTransactionService.confirmMealNutrition(nutritionJob, emptyMap(), null)
        }
        assertEquals(MealStatus.ANALYZING, meal.status)
    }

    // retryMealAnalysis·cancelMealAnalysis·failMealAnalysis

    @Test
    fun `재시도할 때 다른 Worker가 가져간 작업이면 false이고 식사 상태를 바꾸지 않는다`() {
        every { mealAnalysisJobRepository.resetLease(leasedJob.jobId, leasedJob.leaseCount) } returns 0

        val isReset = mealAnalysisJobTransactionService.retryMealAnalysis(leasedJob)

        assertFalse(isReset)
        verify(exactly = 0) { mealRepository.updateMealStatuses(any(), any(), any()) }
    }

    @Test
    fun `재시도하면 lease를 풀고 식사를 WAITING으로 바꾼다`() {
        every { mealAnalysisJobRepository.resetLease(leasedJob.jobId, leasedJob.leaseCount) } returns 1

        val isReset = mealAnalysisJobTransactionService.retryMealAnalysis(leasedJob)

        assertTrue(isReset)
        verify(exactly = 1) { mealAnalysisJobRepository.resetLease(1L, 2) }
        verify(exactly = 1) { mealRepository.updateMealStatuses(listOf(10L), MealStatus.WAITING, now) }
    }

    @Test
    fun `영양 작업을 재시도하면 식사를 WAITING으로 바꾼다`() {
        val nutritionJob = leasedJob.copy(category = MealAnalysisJobCategory.NUTRITION)
        every { mealAnalysisJobRepository.resetLease(nutritionJob.jobId, nutritionJob.leaseCount) } returns 1

        mealAnalysisJobTransactionService.retryMealAnalysis(nutritionJob)

        verify(exactly = 1) { mealRepository.updateMealStatuses(listOf(10L), MealStatus.WAITING, now) }
    }

    @Test
    fun `인식 작업을 재시도하면 lease만 풀고 식사는 RECOGNIZING 그대로 둔다`() {
        val recognitionJob = leasedJob.copy(category = MealAnalysisJobCategory.RECOGNITION)
        every { mealAnalysisJobRepository.resetLease(recognitionJob.jobId, recognitionJob.leaseCount) } returns 1

        val isReset = mealAnalysisJobTransactionService.retryMealAnalysis(recognitionJob)

        assertTrue(isReset)
        verify(exactly = 1) { mealAnalysisJobRepository.resetLease(1L, 2) }
        verify(exactly = 0) { mealRepository.updateMealStatuses(any(), any(), any()) }
    }

    @Test
    fun `시작하지 못한 작업을 되돌릴 때 다른 Worker가 가져간 작업이면 false이고 식사 상태를 바꾸지 않는다`() {
        every { mealAnalysisJobRepository.cancelLease(leasedJob.jobId, leasedJob.leaseCount, 0L) } returns 0

        val isCanceled = mealAnalysisJobTransactionService.cancelMealAnalysis(leasedJob, Duration.ZERO)

        assertFalse(isCanceled)
        verify(exactly = 0) { mealRepository.updateMealStatuses(any(), any(), any()) }
    }

    @Test
    fun `시작하지 못한 작업은 lease와 번호를 되돌리고 식사를 WAITING으로 바꾼다`() {
        every { mealAnalysisJobRepository.cancelLease(leasedJob.jobId, leasedJob.leaseCount, 0L) } returns 1

        val isCanceled = mealAnalysisJobTransactionService.cancelMealAnalysis(leasedJob, Duration.ZERO)

        assertTrue(isCanceled)
        verify(exactly = 1) { mealAnalysisJobRepository.cancelLease(1L, 2, 0L) }
        verify(exactly = 1) { mealRepository.updateMealStatuses(listOf(10L), MealStatus.WAITING, now) }
    }

    @Test
    fun `시작하지 못한 작업을 되돌릴 때 대기 시간을 초로 바꿔 다시 가져갈 시각을 미룬다`() {
        every { mealAnalysisJobRepository.cancelLease(leasedJob.jobId, leasedJob.leaseCount, 60L) } returns 1

        val isCanceled = mealAnalysisJobTransactionService.cancelMealAnalysis(leasedJob, Duration.ofMinutes(1))

        assertTrue(isCanceled)
        verify(exactly = 1) { mealAnalysisJobRepository.cancelLease(1L, 2, 60L) }
    }

    @Test
    fun `시작하지 못한 인식 작업은 lease와 번호만 되돌리고 식사는 RECOGNIZING 그대로 둔다`() {
        val recognitionJob = leasedJob.copy(category = MealAnalysisJobCategory.RECOGNITION)
        every {
            mealAnalysisJobRepository.cancelLease(recognitionJob.jobId, recognitionJob.leaseCount, 60L)
        } returns 1

        val isCanceled = mealAnalysisJobTransactionService.cancelMealAnalysis(recognitionJob, Duration.ofMinutes(1))

        assertTrue(isCanceled)
        verify(exactly = 1) { mealAnalysisJobRepository.cancelLease(1L, 2, 60L) }
        verify(exactly = 0) { mealRepository.updateMealStatuses(any(), any(), any()) }
    }

    @Test
    fun `실패로 끝낼 때 다른 Worker가 가져간 작업이면 아무것도 쓰지 않고 null을 돌려준다`() {
        givenLostJob()

        val result = mealAnalysisJobTransactionService.failMealAnalysis(leasedJob)

        assertNull(result)
        verify(exactly = 0) { mealRepository.findMealWithLockById(any()) }
    }

    @Test
    fun `실패로 끝내면 식사를 FAILED로 바꾸고 알림 값을 돌려준다`() {
        val meal = createLockedMeal()
        givenOwnedJob(meal)

        val result = mealAnalysisJobTransactionService.failMealAnalysis(leasedJob)

        assertEquals(MealStatus.FAILED, meal.status)
        assertEquals(MealStatus.FAILED, result?.status)
        assertEquals(false, result?.isDeleted)
        verify(exactly = 0) { catService.updateLastMealAt(any(), any()) }
    }
}

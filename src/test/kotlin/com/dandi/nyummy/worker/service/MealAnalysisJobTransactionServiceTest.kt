package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisJob
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.AnalysisPushResult
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * leaseMealAnalysisJobs가 잠가 가져온 작업에 lease를 걸고 식사를 ANALYZING으로 바꾸는지, 지워진 식사와 최대 시도를 채운 작업을 건너뛰는지,
 * 확정·재시도·되돌리기·실패가 작업이 아직 그 번호일 때만 결과를 쓰는지 다룬다.
 * SKIP LOCKED로 두 Worker가 겹치지 않는지는 실제 DB가 필요해 여기서 다루지 않는다.
 *
 * lease 테스트의 작업·식사는 id와 lease_count가 생성자 밖의 val이라 mockk로 만든다.
 * 확정·실패 테스트의 식사는 필드가 실제로 바뀌는지 봐야 해서 실제 엔티티를 쓴다.
 */
class MealAnalysisJobTransactionServiceTest {

    private val now = Instant.parse("2026-11-02T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val limit = 50

    private val mealAnalysisJobRepository = mockk<MealAnalysisJobRepository>()
    private val mealRepository = mockk<MealRepository>()
    private val catRepository = mockk<CatRepository>()
    private val catService = mockk<CatService>()

    private val mealAnalysisJobTransactionService = MealAnalysisJobTransactionService(
        mealAnalysisJobRepository = mealAnalysisJobRepository,
        mealRepository = mealRepository,
        catRepository = catRepository,
        catService = catService,
        workerProperties = WorkerProperties(enabled = true, leaseTimeToLive = Duration.ofSeconds(60)),
        clock = clock,
    )

    init {
        every { mealAnalysisJobRepository.updateLease(any(), any()) } answers { firstArg<Collection<Long>>().size }
        every { mealAnalysisJobRepository.deleteAllByIdInBatch(any()) } just Runs
        every { mealRepository.updateMealStatuses(any(), any(), any()) } answers { firstArg<Collection<Long>>().size }
    }

    private fun createJob(id: Long, mealId: Long, leaseCount: Int = 0): MealAnalysisJob = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.mealId } returns mealId
        every { this@mockk.leaseCount } returns leaseCount
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
                ),
                MealAnalysisJobResult(
                    jobId = 2L,
                    mealId = 20L,
                    leaseCount = 2,
                    imageKey = "meals/1/2026/11/02/meal-20.jpg",
                    canRetry = false,
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

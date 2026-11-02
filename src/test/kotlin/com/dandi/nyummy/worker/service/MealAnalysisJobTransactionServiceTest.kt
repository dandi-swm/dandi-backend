package com.dandi.nyummy.worker.service

import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisJob
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.worker.config.WorkerProperties
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
import kotlin.test.assertTrue

/**
 * leaseMealAnalysisJobs가 잠가 가져온 작업에 lease를 걸고 식사를 ANALYZING으로 바꾸는지, 지워진 식사의 작업을 건너뛰는지 다룬다.
 * SKIP LOCKED로 두 Worker가 겹치지 않는지는 실제 DB가 필요해 여기서 다루지 않는다.
 *
 * 작업·식사의 id와 lease_count는 생성자 밖의 val이라 mockk로 만든다.
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
                ),
                MealAnalysisJobResult(
                    jobId = 2L,
                    mealId = 20L,
                    leaseCount = 2,
                    imageKey = "meals/1/2026/11/02/meal-20.jpg",
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
}

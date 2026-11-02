package com.dandi.nyummy.worker

import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import com.dandi.nyummy.worker.service.AnalysisService
import com.dandi.nyummy.worker.service.MealAnalysisJobTransactionService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import kotlin.test.Test

/**
 * pollMealAnalysisJobs가 실행기의 빈 자리만큼(한 번 상한 안에서) 작업을 가져와 넘기고, 거부된 작업을 되돌리는지 다룬다.
 * 실행기는 mockk로 두고, 받은 일을 그 자리에서 바로 실행하게 해 분석 호출까지 확인한다.
 */
class MealAnalysisJobPollerTest {

    private val maxPoolSize = 50
    private val leaseLimit = 10

    private val mealAnalysisJobTransactionService = mockk<MealAnalysisJobTransactionService>()
    private val analysisService = mockk<AnalysisService>()
    private val analysisExecutor = mockk<ThreadPoolTaskExecutor>()

    private val mealAnalysisJobPoller = MealAnalysisJobPoller(
        mealAnalysisJobTransactionService = mealAnalysisJobTransactionService,
        analysisService = analysisService,
        analysisExecutor = analysisExecutor,
        workerProperties = WorkerProperties(enabled = true, leaseLimit = leaseLimit),
    )

    private val firstJob = createJob(jobId = 1L)
    private val secondJob = createJob(jobId = 2L)

    init {
        every { analysisExecutor.maxPoolSize } returns maxPoolSize
        every { analysisExecutor.execute(any()) } answers { firstArg<Runnable>().run() }
        every { analysisService.analyzeMealAnalysisJob(any()) } just Runs
        every { mealAnalysisJobTransactionService.leaseMealAnalysisJobs(any()) } returns listOf(firstJob, secondJob)
        every { mealAnalysisJobTransactionService.cancelMealAnalysis(any()) } returns true
    }

    private fun createJob(jobId: Long) = MealAnalysisJobResult(
        jobId = jobId,
        mealId = jobId * 10,
        leaseCount = 1,
        imageKey = "meals/1/meal-$jobId.jpg",
        canRetry = true,
    )

    private fun givenActiveCount(activeCount: Int) {
        every { analysisExecutor.activeCount } returns activeCount
    }

    @Test
    fun `실행기에 빈 자리가 없으면 작업을 가져가지 않는다`() {
        givenActiveCount(maxPoolSize)

        mealAnalysisJobPoller.pollMealAnalysisJobs()

        verify(exactly = 0) { mealAnalysisJobTransactionService.leaseMealAnalysisJobs(any()) }
    }

    @Test
    fun `빈 자리가 한 번 상한보다 적으면 빈 자리만큼 가져간다`() {
        givenActiveCount(maxPoolSize - 3)

        mealAnalysisJobPoller.pollMealAnalysisJobs()

        verify(exactly = 1) { mealAnalysisJobTransactionService.leaseMealAnalysisJobs(3) }
    }

    @Test
    fun `빈 자리가 한 번 상한보다 많으면 상한만큼 가져간다`() {
        givenActiveCount(0)

        mealAnalysisJobPoller.pollMealAnalysisJobs()

        verify(exactly = 1) { mealAnalysisJobTransactionService.leaseMealAnalysisJobs(leaseLimit) }
    }

    @Test
    fun `가져온 작업마다 실행기에 넘겨 분석한다`() {
        givenActiveCount(0)

        mealAnalysisJobPoller.pollMealAnalysisJobs()

        verify(exactly = 2) { analysisExecutor.execute(any()) }
        verify(exactly = 1) { analysisService.analyzeMealAnalysisJob(firstJob) }
        verify(exactly = 1) { analysisService.analyzeMealAnalysisJob(secondJob) }
    }

    @Test
    fun `실행기가 거부한 작업은 되돌리고 나머지 작업은 계속 넘긴다`() {
        givenActiveCount(0)
        var calls = 0
        every { analysisExecutor.execute(any()) } answers {
            calls++
            if (calls == 1) throw TaskRejectedException("가득 참")
            firstArg<Runnable>().run()
        }

        mealAnalysisJobPoller.pollMealAnalysisJobs()

        verify(exactly = 1) { mealAnalysisJobTransactionService.cancelMealAnalysis(firstJob) }
        verify(exactly = 0) { mealAnalysisJobTransactionService.cancelMealAnalysis(secondJob) }
        verify(exactly = 1) { analysisService.analyzeMealAnalysisJob(secondJob) }
    }

    @Test
    fun `분석 중 예외가 나도 밖으로 던지지 않고 다음 작업을 처리한다`() {
        givenActiveCount(0)
        every { analysisService.analyzeMealAnalysisJob(firstJob) } throws IllegalStateException("예상 밖")

        mealAnalysisJobPoller.pollMealAnalysisJobs()

        verify(exactly = 1) { analysisService.analyzeMealAnalysisJob(secondJob) }
    }
}

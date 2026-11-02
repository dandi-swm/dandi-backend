package com.dandi.nyummy.worker

import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
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
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * pollMealAnalysisJobs가 실행기의 빈 자리만큼(한 번 상한 안에서) 작업을 가져와 넘기고, 거부된 작업을 되돌리는지,
 * stop이 처리 중인 작업을 기다리고 끝나지 않은 작업을 정리하는지 다룬다.
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
        // 컨텍스트가 뜰 때 Spring이 하듯 시작해 둔다. 시작 전이나 종료 뒤에는 작업을 가져가지 않는다.
        mealAnalysisJobPoller.start()
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

    // stop

    private val threadPoolExecutor = mockk<ThreadPoolExecutor>(relaxed = true)
    private val heldTasks = mutableListOf<Runnable>()

    /** 실행기가 받은 일을 바로 실행하지 않고 쥐고 있게 해, 그 작업이 처리 중으로 남게 한다. */
    private fun givenExecutorHoldsTasks() {
        every { analysisExecutor.threadPoolExecutor } returns threadPoolExecutor
        every { analysisExecutor.execute(any()) } answers { heldTasks.add(firstArg()) }
        every { analysisService.handleAnalysisFailure(any(), any()) } just Runs
    }

    private fun givenAllTasksFinishInTime(isFinished: Boolean) {
        every { threadPoolExecutor.awaitTermination(any(), any()) } returns isFinished
    }

    @Test
    fun `종료한 뒤에는 작업을 가져가지 않는다`() {
        givenActiveCount(0)
        givenExecutorHoldsTasks()
        givenAllTasksFinishInTime(true)

        mealAnalysisJobPoller.stop()
        mealAnalysisJobPoller.pollMealAnalysisJobs()

        verify(exactly = 0) { mealAnalysisJobTransactionService.leaseMealAnalysisJobs(any()) }
    }

    @Test
    fun `처리 중인 작업이 대기 시간 안에 끝나면 아무것도 정리하지 않는다`() {
        givenActiveCount(0)
        givenExecutorHoldsTasks()
        givenAllTasksFinishInTime(true)
        mealAnalysisJobPoller.pollMealAnalysisJobs()

        mealAnalysisJobPoller.stop()

        verify(exactly = 1) { threadPoolExecutor.shutdown() }
        verify(exactly = 1) { threadPoolExecutor.awaitTermination(40_000L, TimeUnit.MILLISECONDS) }
        verify(exactly = 0) { analysisService.handleAnalysisFailure(any(), any()) }
        verify(exactly = 0) { threadPoolExecutor.shutdownNow() }
    }

    @Test
    fun `대기 시간 안에 끝나지 않은 작업은 일시 실패 규칙으로 정리하고 강제로 끝낸다`() {
        givenActiveCount(0)
        givenExecutorHoldsTasks()
        givenAllTasksFinishInTime(false)
        mealAnalysisJobPoller.pollMealAnalysisJobs()

        mealAnalysisJobPoller.stop()

        listOf(firstJob, secondJob).forEach { job ->
            verify(exactly = 1) {
                analysisService.handleAnalysisFailure(job, match { it.category == AnalysisFailureCategory.UNAVAILABLE })
            }
        }
        verify(exactly = 1) { threadPoolExecutor.shutdownNow() }
    }

    @Test
    fun `이미 끝난 작업은 종료할 때 정리하지 않는다`() {
        givenActiveCount(0)
        givenExecutorHoldsTasks()
        givenAllTasksFinishInTime(false)
        mealAnalysisJobPoller.pollMealAnalysisJobs()
        heldTasks.first().run()

        mealAnalysisJobPoller.stop()

        verify(exactly = 0) { analysisService.handleAnalysisFailure(firstJob, any()) }
        verify(exactly = 1) { analysisService.handleAnalysisFailure(secondJob, any()) }
    }

    @Test
    fun `종료할 때 한 작업을 정리하지 못해도 나머지 작업을 정리한다`() {
        givenActiveCount(0)
        givenExecutorHoldsTasks()
        givenAllTasksFinishInTime(false)
        every { analysisService.handleAnalysisFailure(firstJob, any()) } throws IllegalStateException("DB 오류")
        mealAnalysisJobPoller.pollMealAnalysisJobs()

        mealAnalysisJobPoller.stop()

        verify(exactly = 1) { analysisService.handleAnalysisFailure(secondJob, any()) }
        verify(exactly = 1) { threadPoolExecutor.shutdownNow() }
    }
}

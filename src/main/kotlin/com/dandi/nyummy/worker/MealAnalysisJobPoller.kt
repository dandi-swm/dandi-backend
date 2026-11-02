package com.dandi.nyummy.worker

import com.dandi.nyummy.worker.config.ConditionalOnWorkerEnabled
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.service.AnalysisService
import com.dandi.nyummy.worker.service.MealAnalysisJobTransactionService
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import kotlin.math.min

/**
 * 1초마다 DB 큐(meal_analysis_jobs)에서 작업을 lease로 가져와 분석 실행기에 넘긴다.
 *
 * 실행기의 빈 자리만큼만 가져가므로, 가져간 작업은 곧바로 처리가 시작된다. lease 시간은 큐에서 기다리는 동안이 아니라
 * 처리하는 동안에만 흐른다. 빈 자리는 실행기의 activeCount로 세는데 근사값이라, 드물게 실행기가 작업을 거부하면
 * 시도 횟수를 쓰지 않게 되돌린다.
 */
@Component
@ConditionalOnWorkerEnabled
class MealAnalysisJobPoller(
    private val mealAnalysisJobTransactionService: MealAnalysisJobTransactionService,
    private val analysisService: AnalysisService,
    private val analysisExecutor: ThreadPoolTaskExecutor,
    private val workerProperties: WorkerProperties,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(MealAnalysisJobPoller::class.java)

        private const val POLLING_DELAY_MILLIS = 1_000L
    }

    /**
     * 이전 polling이 끝난 뒤 1초를 쉬고 다시 돈다. 처음 시작은 0~1초 사이 무작위로 늦춰, 여러 Worker가 함께 떠도
     * 같은 순간에 DB를 조회하지 않게 한다.
     */
    @Scheduled(
        fixedDelay = POLLING_DELAY_MILLIS,
        initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(1000)}",
    )
    fun pollMealAnalysisJobs() {
        val freeSlots = analysisExecutor.maxPoolSize - analysisExecutor.activeCount
        if (freeSlots <= 0) {
            return
        }

        val jobs = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(min(freeSlots, workerProperties.leaseLimit))

        jobs.forEach { job ->
            try {
                analysisExecutor.execute {
                    // 실행기 스레드에서 새어 나온 예외는 로거를 거치지 않고 사라지므로 여기서 남긴다.
                    try {
                        analysisService.analyzeMealAnalysisJob(job)
                    } catch (e: RuntimeException) {
                        logger.error("분석 작업 처리 중 오류가 났습니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount, e)
                    }
                }
            } catch (e: TaskRejectedException) {
                logger.warn("실행기가 가득 차 분석 작업을 되돌립니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount)
                mealAnalysisJobTransactionService.cancelMealAnalysis(job)
            }
        }
    }
}

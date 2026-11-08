package com.dandi.nyummy.worker

import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.worker.config.ConditionalOnAnalysisWorkerEnabled
import com.dandi.nyummy.worker.config.WorkerJobProperties
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import com.dandi.nyummy.worker.service.AnalysisService
import com.dandi.nyummy.worker.service.MealAnalysisJobTransactionService
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * [WorkerJobProperties.pollingDelay](기본 1초)마다 DB 큐(meal_analysis_jobs)에서 작업을 lease로 가져와 분석 실행기에 넘긴다.
 *
 * 실행기의 빈 자리만큼만 가져가므로, 가져간 작업은 곧바로 처리가 시작된다. lease 시간은 큐에서 기다리는 동안이 아니라
 * 처리하는 동안에만 흐른다. 빈 자리는 실행기의 activeCount로 세는데 근사값이라, 드물게 실행기가 작업을 거부하면
 * 시도 횟수를 쓰지 않게 되돌린다.
 *
 * 종료할 때([stop])는 새 작업을 가져가지 않고, 처리 중인 작업이 끝나기를 [WorkerProperties.shutdownTimeout]만큼 기다린다.
 * 그 안에 끝나지 않은 작업은 정상 처리 시간을 넘긴 시도로 보고 일시적인 실패와 같은 규칙(재시도할 수 있으면 대기로,
 * 마지막 시도면 FAILED)으로 정리한다. 번호를 내려 되돌리지 않는 이유는, 아직 돌고 있을 수 있는 스레드가 같은 번호로
 * 결과를 쓰지 못하게(fencing) 하기 위해서다.
 */
@Component
@ConditionalOnAnalysisWorkerEnabled
class MealAnalysisJobPoller(
    private val mealAnalysisJobTransactionService: MealAnalysisJobTransactionService,
    private val analysisService: AnalysisService,
    private val analysisExecutor: ThreadPoolTaskExecutor,
    private val workerProperties: WorkerProperties,
) : SmartLifecycle {
    companion object {
        private val logger = LoggerFactory.getLogger(MealAnalysisJobPoller::class.java)
    }

    @Volatile
    private var running = false

    // 실행기에 넘겨 아직 끝나지 않은 작업. 종료할 때 대기 시간 안에 끝나지 않은 작업을 찾는 데 쓴다.
    private val inProgressJobs: MutableSet<MealAnalysisJobResult> = ConcurrentHashMap.newKeySet()

    /**
     * 이전 polling이 끝난 뒤 [WorkerJobProperties.pollingDelay](기본 1초)를 쉬고 다시 돈다. 처음 시작은 0~1초 사이
     * 무작위로 늦춰, 여러 Worker가 함께 떠도 같은 순간에 DB를 조회하지 않게 한다.
     */
    @Scheduled(
        fixedDelayString = "\${app.worker.analysis.polling-delay:1s}",
        initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(1000)}",
    )
    fun pollMealAnalysisJobs() {
        if (!running) {
            return
        }

        val freeSlots = analysisExecutor.maxPoolSize - analysisExecutor.activeCount
        if (freeSlots <= 0) {
            return
        }

        val jobs = mealAnalysisJobTransactionService.leaseMealAnalysisJobs(
            min(freeSlots, workerProperties.analysis.leaseLimit),
        )

        jobs.forEach { job ->
            inProgressJobs.add(job)
            try {
                analysisExecutor.execute {
                    // 실행기 스레드에서 새어 나온 예외는 로거를 거치지 않고 사라지므로 여기서 남긴다.
                    try {
                        analysisService.analyzeMealAnalysisJob(job)
                    } catch (e: RuntimeException) {
                        logger.error("분석 작업 처리 중 오류가 났습니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount, e)
                    } finally {
                        inProgressJobs.remove(job)
                    }
                }
            } catch (e: TaskRejectedException) {
                inProgressJobs.remove(job)
                logger.warn("실행기가 가득 차 분석 작업을 되돌립니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount)
                mealAnalysisJobTransactionService.cancelMealAnalysis(job, Duration.ZERO)
            }
        }
    }

    override fun start() {
        running = true
    }

    /**
     * 새 작업을 가져가지 않게 하고, 처리 중인 작업이 끝나기를 기다린 뒤, 끝나지 않은 작업을 정리한다.
     *
     * 실행기는 컨텍스트가 닫힐 때 Spring이 먼저 새 작업만 막아 두고(shutdown), 처리 중인 작업은 끊지 않는다.
     * 여기서 그 작업들이 끝나기를 기다린다. DB 연결 풀은 이 단계 뒤에 닫히므로 정리 트랜잭션을 쓸 수 있다.
     */
    override fun stop() {
        running = false

        val executor = analysisExecutor.threadPoolExecutor
        executor.shutdown()
        val isTerminated = try {
            executor.awaitTermination(workerProperties.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (isTerminated) {
            return
        }

        val unfinishedJobs = inProgressJobs.toList()
        logger.warn("종료 대기 시간 안에 끝나지 않은 분석 작업을 정리합니다: mealIds={}", unfinishedJobs.map { it.mealId })
        unfinishedJobs.forEach { job ->
            try {
                analysisService.handleAnalysisFailure(
                    job,
                    AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "Worker 종료 대기 시간을 넘겼습니다."),
                )
            } catch (e: RuntimeException) {
                logger.error("종료 중 분석 작업을 정리하지 못했습니다: mealId={}, leaseCount={}", job.mealId, job.leaseCount, e)
            }
        }
        executor.shutdownNow()
    }

    override fun isRunning(): Boolean = running
}

package com.dandi.nyummy.worker

import com.dandi.nyummy.infra.ai.report.ReportGenerationFailureCategory
import com.dandi.nyummy.infra.ai.report.ReportGenerationFailureException
import com.dandi.nyummy.worker.config.ConditionalOnReportWorkerEnabled
import com.dandi.nyummy.worker.config.WorkerJobProperties
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.ReportGenerationJobResult
import com.dandi.nyummy.worker.service.ReportGenerationJobTransactionService
import com.dandi.nyummy.worker.service.ReportGenerationService
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
 * [WorkerJobProperties.pollingDelay](기본 1초)마다 DB 큐(report_generation_jobs)에서 작업을 lease로 가져와 리포트 실행기에 넘긴다.
 * 빈 자리만큼만 가져가는 방식과 종료 처리는 [MealAnalysisJobPoller]와 같다.
 *
 * 최대 시도 수를 채워 가져가지 않은 작업은 lease 트랜잭션이 끝난 뒤 이 스레드에서 바로 FAILED로 끝낸다(DB만 쓰므로 짧다).
 *
 * dev처럼 한 서비스에서 분석과 리포트를 함께 켜면 종료할 때 두 폴러가 차례로 기다려 대기 시간이 최대 두 배가 된다.
 * prod는 종류마다 다른 ECS 서비스라 겹치지 않는다.
 */
@Component
@ConditionalOnReportWorkerEnabled
class ReportGenerationJobPoller(
    private val reportGenerationJobTransactionService: ReportGenerationJobTransactionService,
    private val reportGenerationService: ReportGenerationService,
    private val reportExecutor: ThreadPoolTaskExecutor,
    private val workerProperties: WorkerProperties,
) : SmartLifecycle {
    companion object {
        private val logger = LoggerFactory.getLogger(ReportGenerationJobPoller::class.java)
    }

    @Volatile
    private var running = false

    // 실행기에 넘겨 아직 끝나지 않은 작업. 종료할 때 대기 시간 안에 끝나지 않은 작업을 찾는 데 쓴다.
    private val inProgressJobs: MutableSet<ReportGenerationJobResult> = ConcurrentHashMap.newKeySet()

    @Scheduled(
        fixedDelayString = "\${app.worker.report.polling-delay:1s}",
        initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(1000)}",
    )
    fun pollReportGenerationJobs() {
        if (!running) {
            return
        }

        val freeSlots = reportExecutor.maxPoolSize - reportExecutor.activeCount
        if (freeSlots <= 0) {
            return
        }

        val leaseResult = reportGenerationJobTransactionService.leaseReportGenerationJobs(
            min(freeSlots, workerProperties.report.leaseLimit),
        )

        leaseResult.exhaustedJobs.forEach { job ->
            try {
                reportGenerationService.failExhaustedReportGeneration(job)
            } catch (e: RuntimeException) {
                logger.error("최대 시도 횟수를 채운 리포트를 끝내지 못했습니다: reportId={}", job.reportId, e)
            }
        }

        leaseResult.leasedJobs.forEach { job ->
            inProgressJobs.add(job)
            try {
                reportExecutor.execute {
                    // 실행기 스레드에서 새어 나온 예외는 로거를 거치지 않고 사라지므로 여기서 남긴다.
                    try {
                        reportGenerationService.generateReport(job)
                    } catch (e: RuntimeException) {
                        logger.error(
                            "리포트 생성 작업 처리 중 오류가 났습니다: reportId={}, leaseCount={}",
                            job.reportId,
                            job.leaseCount,
                            e,
                        )
                    } finally {
                        inProgressJobs.remove(job)
                    }
                }
            } catch (e: TaskRejectedException) {
                inProgressJobs.remove(job)
                logger.warn("실행기가 가득 차 리포트 생성 작업을 되돌립니다: reportId={}, leaseCount={}", job.reportId, job.leaseCount)
                reportGenerationJobTransactionService.cancelReportGeneration(job, Duration.ZERO)
            }
        }
    }

    override fun start() {
        running = true
    }

    /**
     * 새 작업을 가져가지 않게 하고, 처리 중인 작업이 끝나기를 기다린 뒤, 끝나지 않은 작업을 일시적인 실패와 같은 규칙으로 정리한다.
     * 번호를 내려 되돌리지 않는 이유는 [MealAnalysisJobPoller.stop]과 같다.
     */
    override fun stop() {
        running = false

        val executor = reportExecutor.threadPoolExecutor
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
        logger.warn("종료 대기 시간 안에 끝나지 않은 리포트 생성 작업을 정리합니다: reportIds={}", unfinishedJobs.map { it.reportId })
        unfinishedJobs.forEach { job ->
            try {
                reportGenerationService.handleReportGenerationFailure(
                    job,
                    ReportGenerationFailureException(
                        ReportGenerationFailureCategory.UNAVAILABLE,
                        "Worker 종료 대기 시간을 넘겼습니다.",
                    ),
                )
            } catch (e: RuntimeException) {
                logger.error("종료 중 리포트 생성 작업을 정리하지 못했습니다: reportId={}", job.reportId, e)
            }
        }
        executor.shutdownNow()
    }

    override fun isRunning(): Boolean = running
}

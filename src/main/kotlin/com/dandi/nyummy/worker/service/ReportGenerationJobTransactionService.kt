package com.dandi.nyummy.worker.service

import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.report.entity.ReportGenerationJob
import com.dandi.nyummy.report.enum.ReportPurchaseRefundCategory
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.report.repository.ReportGenerationJobRepository
import com.dandi.nyummy.report.repository.ReportRepository
import com.dandi.nyummy.report.service.ReportPurchaseRefundService
import com.dandi.nyummy.worker.config.ConditionalOnReportWorkerEnabled
import com.dandi.nyummy.worker.config.WorkerJobProperties
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.ReportGenerationJobResult
import com.dandi.nyummy.worker.dto.ReportGenerationLeaseResult
import com.dandi.nyummy.worker.dto.ReportPushResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * 리포트 Worker가 쓰는 짧은 트랜잭션을 모은다. Gemini 호출은 이 트랜잭션들 밖에서 한다.
 *
 * 잠그는 순서는 코인(coins) → 작업(report_generation_jobs) → 리포트(reports) → 구매·환불이다. 구매·사용자 취소도 같은 순서로 잠근다.
 * 코인을 쓰지 않는 트랜잭션(lease·확정·재시도)은 코인을 건너뛰고 작업부터 잠근다. 빠진 칸이 있어도 순서가 거꾸로 되지는 않는다.
 *
 * 결과를 쓰는 메서드는 모두 작업이 아직 그 번호(leaseCount)일 때만 쓴다(fencing). lease가 만료돼 다른 Worker가 가져갔거나
 * 사용자가 취소해 작업이 지워졌으면 늦게 끝난 Worker는 아무것도 쓰지 않고 끝난다.
 */
@Service
@ConditionalOnReportWorkerEnabled
class ReportGenerationJobTransactionService(
    private val reportGenerationJobRepository: ReportGenerationJobRepository,
    private val reportRepository: ReportRepository,
    private val reportPurchaseRefundService: ReportPurchaseRefundService,
    private val coinService: CoinService,
    private val workerProperties: WorkerProperties,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ReportGenerationJobTransactionService::class.java)
    }

    /**
     * 대기 중이거나 lease가 만료된 작업을 [limit]개까지 lease로 가져가고, 그 리포트들을 GENERATING으로 바꾼다.
     *
     * 작업 행을 잠근 채(SKIP LOCKED) lease와 lease_count를 올리므로 같은 작업을 두 Worker가 함께 가져가지 않는다.
     * 이미 [WorkerJobProperties.maxLeaseCount]번 가져갔는데 또 lease가 만료된 작업은 가져가지 않고 따로 돌려준다.
     * 그 작업을 FAILED로 끝내며 환불하려면 코인 행을 먼저 잠가야 하는데, 이 트랜잭션은 이미 작업 행을 잠갔기 때문이다.
     */
    @Transactional
    fun leaseReportGenerationJobs(limit: Int): ReportGenerationLeaseResult {
        val jobs = reportGenerationJobRepository.findReportGenerationJobsWithLock(limit)
        if (jobs.isEmpty()) {
            return ReportGenerationLeaseResult(emptyList(), emptyList())
        }

        val reportsById = reportRepository.findAllById(jobs.map { it.reportId }).associateBy { it.id }
        val maxLeaseCount = workerProperties.report.maxLeaseCount
        val (leasableJobs, exhaustedJobs) = jobs.partition { it.leaseCount < maxLeaseCount }

        if (leasableJobs.isNotEmpty()) {
            reportGenerationJobRepository.updateLease(
                leasableJobs.map { it.id },
                workerProperties.report.leaseTimeToLive.toSeconds(),
            )
            reportRepository.updateReportStatuses(
                leasableJobs.map { it.reportId },
                ReportStatus.IN_PROGRESS_STATUSES,
                ReportStatus.GENERATING,
                clock.instant(),
            )
        }

        fun convertJobResult(job: ReportGenerationJob, leaseCount: Int) = reportsById[job.reportId]?.let { report ->
            ReportGenerationJobResult(
                jobId = job.id,
                reportId = report.id,
                userId = report.userId,
                startDate = report.startDate,
                leaseCount = leaseCount,
                canRetry = leaseCount < maxLeaseCount,
                createdAt = job.createdAt,
            )
        } ?: run {
            logger.error("리포트 생성 작업의 리포트가 없습니다: jobId={}, reportId={}", job.id, job.reportId)
            null
        }

        if (exhaustedJobs.isNotEmpty()) {
            logger.warn("최대 시도 횟수를 채운 리포트 생성 작업을 FAILED로 끝냅니다: reportIds={}", exhaustedJobs.map { it.reportId })
        }
        return ReportGenerationLeaseResult(
            leasedJobs = leasableJobs.mapNotNull { convertJobResult(it, it.leaseCount + 1) },
            exhaustedJobs = exhaustedJobs.mapNotNull { convertJobResult(it, it.leaseCount) },
        )
    }

    /**
     * 생성 결과로 리포트를 COMPLETED로 확정한다. 작업은 지운다.
     *
     * 사용자 취소가 먼저 커밋되었으면 작업이 이미 지워져 0행이라 결과를 버린다. 확정이 먼저 커밋되면 뒤에 온 취소는
     * 완료된 리포트를 보고 거부된다(먼저 커밋한 쪽이 이긴다).
     *
     * @return 알림에 쓸 값. 이 Worker의 작업이 아니면 null
     */
    @Transactional
    fun confirmReportGeneration(
        job: ReportGenerationJobResult,
        title: String,
        viewType: String,
        content: String,
    ): ReportPushResult? {
        if (!deleteJob(job)) {
            return null
        }

        val report = reportRepository.findReportWithLockById(job.reportId)
            ?: throw IllegalStateException("리포트 생성 작업의 리포트가 없습니다: reportId=${job.reportId}")
        report.confirm(title, viewType, content, clock.instant())

        return ReportPushResult(
            job.reportId,
            job.userId,
            job.startDate,
            ReportStatus.COMPLETED,
            refundedCoinQuantity = 0,
        )
    }

    /**
     * 시도 하나를 쓴 채로 작업을 대기로 되돌리고 리포트를 WAITING으로 둔다. [delay]가 지난 뒤의 polling에서 다시 가져간다.
     *
     * @return 이 Worker의 작업이라 되돌렸으면 true
     */
    @Transactional
    fun retryReportGeneration(job: ReportGenerationJobResult, delay: Duration): Boolean {
        if (reportGenerationJobRepository.retryLease(job.jobId, job.leaseCount, delay.toSeconds()) == 0) {
            logger.info("다른 Worker가 가져갔거나 취소된 리포트 생성 작업이라 되돌리지 않습니다: jobId={}", job.jobId)
            return false
        }

        updateReportStatus(job, ReportStatus.WAITING)
        return true
    }

    /**
     * 생성을 시작하지 못한 작업을 가져간 적 없는 것처럼 되돌린다. 번호를 하나 내려 시도 횟수를 쓰지 않으며, 리포트를 WAITING으로 둔다.
     *
     * 실행기가 가득 차 작업을 받지 못했거나 설정 오류로 Gemini가 모든 요청을 거부할 때 쓴다. 설정 오류일 때의 위험은
     * [MealAnalysisJobTransactionService.cancelMealAnalysis]와 같다.
     *
     * @return 이 Worker의 작업이라 되돌렸으면 true
     */
    @Transactional
    fun cancelReportGeneration(job: ReportGenerationJobResult, delay: Duration): Boolean {
        if (reportGenerationJobRepository.cancelLease(job.jobId, job.leaseCount, delay.toSeconds()) == 0) {
            logger.info("다른 Worker가 가져갔거나 취소된 리포트 생성 작업이라 되돌리지 않습니다: jobId={}", job.jobId)
            return false
        }

        updateReportStatus(job, ReportStatus.WAITING)
        return true
    }

    /**
     * 리포트를 FAILED로 끝내고, 유료 구매였으면 코인을 돌려준다. 작업은 지운다.
     *
     * 코인 행 잠금이 첫 문장이다. 사용자 취소도 코인 행을 먼저 잠그므로 둘이 같은 리포트를 동시에 끝내려 해도 하나씩 처리되고,
     * 뒤에 온 쪽은 작업이 이미 지워져(0행) 아무것도 하지 않는다. 환불은 구매당 한 번이라 유니크 제약이 마지막 안전망이다.
     *
     * @return 알림에 쓸 값. 이 Worker의 작업이 아니면(다른 Worker가 가져갔거나 사용자가 취소했으면) null
     */
    @Transactional
    fun failReportGeneration(job: ReportGenerationJobResult): ReportPushResult? {
        val coin = coinService.lockCoin(job.userId)
        if (!deleteJob(job)) {
            return null
        }

        updateReportStatus(job, ReportStatus.FAILED)
        val refundedCoinQuantity = reportPurchaseRefundService.createReportPurchaseRefund(
            coin,
            job.reportId,
            ReportPurchaseRefundCategory.FAILED,
        )

        return ReportPushResult(job.reportId, job.userId, job.startDate, ReportStatus.FAILED, refundedCoinQuantity)
    }

    /** 작업이 아직 이 Worker의 번호면 지운다. 지운 행이 없으면 다른 Worker가 가져갔거나, 취소되었거나, 이미 끝난 작업이다. */
    private fun deleteJob(job: ReportGenerationJobResult): Boolean {
        if (reportGenerationJobRepository.deleteByIdAndLeaseCount(job.jobId, job.leaseCount) == 0) {
            logger.info(
                "다른 Worker가 가져갔거나 취소된 리포트 생성 작업이라 결과를 쓰지 않습니다: jobId={}, leaseCount={}",
                job.jobId,
                job.leaseCount,
            )
            return false
        }
        return true
    }

    // 작업이 아직 이 Worker의 것이었으니 리포트는 끝나지 않은 상태여야 한다. 아니면 데이터가 어긋난 것이라 남긴다.
    private fun updateReportStatus(job: ReportGenerationJobResult, status: ReportStatus) {
        val updatedCount = reportRepository.updateReportStatuses(
            listOf(job.reportId),
            ReportStatus.IN_PROGRESS_STATUSES,
            status,
            clock.instant(),
        )
        if (updatedCount == 0) {
            logger.error("끝나지 않은 상태여야 할 리포트를 {}로 바꾸지 못했습니다: reportId={}", status, job.reportId)
        }
    }
}

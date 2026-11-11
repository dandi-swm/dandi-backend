package com.dandi.nyummy.worker.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.report.entity.Report
import com.dandi.nyummy.report.entity.ReportGenerationJob
import com.dandi.nyummy.report.enum.ReportPurchaseRefundCategory
import com.dandi.nyummy.report.enum.ReportStatus
import com.dandi.nyummy.report.repository.ReportGenerationJobRepository
import com.dandi.nyummy.report.repository.ReportRepository
import com.dandi.nyummy.report.service.ReportPurchaseRefundService
import com.dandi.nyummy.worker.config.WorkerJobProperties
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.ReportGenerationJobResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportGenerationJobTransactionServiceTest {

    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val userId = 1L
    private val coin = Coin(userId)
    private val sunday = LocalDate.parse("2026-11-01")

    private val reportGenerationJobRepository = mockk<ReportGenerationJobRepository>()
    private val reportRepository = mockk<ReportRepository>()
    private val reportPurchaseRefundService = mockk<ReportPurchaseRefundService>()
    private val coinService = mockk<CoinService>()

    private val service = ReportGenerationJobTransactionService(
        reportGenerationJobRepository = reportGenerationJobRepository,
        reportRepository = reportRepository,
        reportPurchaseRefundService = reportPurchaseRefundService,
        coinService = coinService,
        workerProperties = WorkerProperties(
            enabled = true,
            report = WorkerJobProperties(maxLeaseCount = 2, leaseTimeToLive = Duration.ofSeconds(120)),
        ),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    init {
        every { reportGenerationJobRepository.updateLease(any(), any()) } answers { firstArg<Collection<Long>>().size }
        every { reportRepository.updateReportStatuses(any(), any(), any(), any()) } answers {
            firstArg<Collection<Long>>().size
        }
        every { coinService.lockCoin(userId) } returns coin
    }

    private fun job(id: Long, reportId: Long, leaseCount: Int) = ReportGenerationJob(reportId, language = null).also {
        ReflectionTestUtils.setField(it, "id", id)
        ReflectionTestUtils.setField(it, "leaseCount", leaseCount)
        it.createdAt = now.minusSeconds(30)
    }

    private fun report(id: Long, status: ReportStatus = ReportStatus.WAITING) = Report(userId, sunday).also {
        ReflectionTestUtils.setField(it, "id", id)
        it.status = status
    }

    private fun jobResult(leaseCount: Int = 1) = ReportGenerationJobResult(
        jobId = 1L,
        reportId = 10L,
        userId = userId,
        startDate = sunday,
        leaseCount = leaseCount,
        canRetry = leaseCount < 2,
        createdAt = now.minusSeconds(30),
        language = Language.KO,
    )

    // leaseReportGenerationJobs

    @Test
    fun `시도가 남은 작업은 lease를 잡고 리포트를 생성 중으로 바꾸며 번호를 하나 올려 돌려준다`() {
        every { reportGenerationJobRepository.findReportGenerationJobsWithLock(10) } returns listOf(job(1, 10, 0))
        every { reportRepository.findAllById(listOf(10L)) } returns listOf(report(10))

        val result = service.leaseReportGenerationJobs(10)

        assertEquals(listOf(1), result.leasedJobs.map { it.leaseCount })
        assertEquals(userId, result.leasedJobs.single().userId)
        assertEquals(true, result.leasedJobs.single().canRetry)
        verify { reportGenerationJobRepository.updateLease(listOf(1L), 120) }
        verify {
            reportRepository.updateReportStatuses(
                listOf(10L),
                ReportStatus.IN_PROGRESS_STATUSES,
                ReportStatus.GENERATING,
                now,
            )
        }
    }

    @Test
    fun `최대 시도 수를 채운 작업은 가져가지 않고 번호를 그대로 따로 돌려준다`() {
        every { reportGenerationJobRepository.findReportGenerationJobsWithLock(10) } returns
            listOf(job(1, 10, 1), job(2, 20, 2))
        every { reportRepository.findAllById(listOf(10L, 20L)) } returns listOf(report(10), report(20))

        val result = service.leaseReportGenerationJobs(10)

        assertEquals(listOf(10L), result.leasedJobs.map { it.reportId })
        assertEquals(false, result.leasedJobs.single().canRetry)
        assertEquals(listOf(20L), result.exhaustedJobs.map { it.reportId })
        assertEquals(2, result.exhaustedJobs.single().leaseCount)
        verify { reportGenerationJobRepository.updateLease(listOf(1L), any()) }
        verify { reportRepository.updateReportStatuses(listOf(10L), any(), ReportStatus.GENERATING, any()) }
    }

    // confirmReportGeneration

    @Test
    fun `내 번호의 작업을 지운 뒤 리포트를 잠가 완료로 확정한다`() {
        val report = report(10, ReportStatus.GENERATING)
        every { reportGenerationJobRepository.deleteByIdAndLeaseCount(1, 1) } returns 1
        every { reportRepository.findReportWithLockById(10) } returns report

        val pushResult = service.confirmReportGeneration(jobResult(), "제목", "NutritionBalance", "{}")

        assertEquals(ReportStatus.COMPLETED, report.status)
        assertEquals("제목", report.title)
        assertEquals(now, report.completedAt)
        assertEquals(ReportStatus.COMPLETED, pushResult?.status)
        verifyOrder {
            reportGenerationJobRepository.deleteByIdAndLeaseCount(1, 1)
            reportRepository.findReportWithLockById(10)
        }
    }

    @Test
    fun `작업이 이미 지워졌으면(취소·다른 Worker) 결과를 버린다`() {
        every { reportGenerationJobRepository.deleteByIdAndLeaseCount(1, 1) } returns 0

        assertNull(service.confirmReportGeneration(jobResult(), "제목", "NutritionBalance", "{}"))
        verify(exactly = 0) { reportRepository.findReportWithLockById(any()) }
    }

    // failReportGeneration

    @Test
    fun `코인 행을 먼저 잠그고 작업을 지운 뒤 리포트를 실패로 바꾸고 환불한다`() {
        every { reportGenerationJobRepository.deleteByIdAndLeaseCount(1, 1) } returns 1
        every {
            reportPurchaseRefundService.createReportPurchaseRefund(coin, 10, ReportPurchaseRefundCategory.FAILED)
        } returns 800

        val pushResult = service.failReportGeneration(jobResult())

        assertEquals(ReportStatus.FAILED, pushResult?.status)
        assertEquals(800, pushResult?.refundedCoinQuantity)
        verifyOrder {
            coinService.lockCoin(userId)
            reportGenerationJobRepository.deleteByIdAndLeaseCount(1, 1)
            reportRepository.updateReportStatuses(
                listOf(10L),
                ReportStatus.IN_PROGRESS_STATUSES,
                ReportStatus.FAILED,
                now,
            )
            reportPurchaseRefundService.createReportPurchaseRefund(coin, 10, ReportPurchaseRefundCategory.FAILED)
        }
    }

    @Test
    fun `작업이 이미 지워졌으면 실패로 바꾸지도 환불하지도 않는다`() {
        every { reportGenerationJobRepository.deleteByIdAndLeaseCount(1, 1) } returns 0

        assertNull(service.failReportGeneration(jobResult()))
        verify(exactly = 0) { reportRepository.updateReportStatuses(any(), any(), any(), any()) }
        verify(exactly = 0) { reportPurchaseRefundService.createReportPurchaseRefund(any(), any(), any()) }
    }

    // retryReportGeneration

    @Test
    fun `재시도는 시도를 쓴 채로 미루고 리포트를 대기로 되돌린다`() {
        every { reportGenerationJobRepository.retryLease(1, 1, 30) } returns 1

        assertEquals(true, service.retryReportGeneration(jobResult(), Duration.ofSeconds(30)))
        verify { reportRepository.updateReportStatuses(listOf(10L), any(), ReportStatus.WAITING, now) }
    }

    @Test
    fun `내 작업이 아니면 재시도로 되돌리지 않는다`() {
        every { reportGenerationJobRepository.retryLease(1, 1, 30) } returns 0

        assertEquals(false, service.retryReportGeneration(jobResult(), Duration.ofSeconds(30)))
        verify(exactly = 0) { reportRepository.updateReportStatuses(any(), any(), any(), any()) }
    }
}

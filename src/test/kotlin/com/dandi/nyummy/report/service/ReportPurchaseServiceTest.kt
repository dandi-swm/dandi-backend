package com.dandi.nyummy.report.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.report.dto.CreateReportPurchaseRequest
import com.dandi.nyummy.report.enum.ReportPurchaseOutcome
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessResourceFailureException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportPurchaseServiceTest {

    private val userId = 1L
    private val idempotencyKey = "key-1"
    private val request = CreateReportPurchaseRequest(startDate = LocalDate.parse("2026-11-01"), purchasePrice = 0)

    private val reportPurchaseTransactionService = mockk<ReportPurchaseTransactionService>()
    private val reportPurchaseAttemptService = mockk<ReportPurchaseAttemptService>()

    private val service = ReportPurchaseService(reportPurchaseTransactionService, reportPurchaseAttemptService)

    init {
        every {
            reportPurchaseAttemptService.createReportPurchaseAttempt(any(), any(), any(), any(), any(), any())
        } just runs
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun purchase() = service.createReportPurchase(userId, idempotencyKey, request, "1.0.0")

    private fun givenTransactionThrows(exception: Exception) {
        every { reportPurchaseTransactionService.createReportPurchase(userId, idempotencyKey, request, "1.0.0") } throws
            exception
    }

    @Test
    fun `거부되면 롤백 뒤에 거부 사유로 시도를 남기고 같은 에러로 응답한다`() {
        givenTransactionThrows(BusinessException(ReportErrorCode.NOT_ENOUGH_MEALS))

        assertBusinessException(ReportErrorCode.NOT_ENOUGH_MEALS) { purchase() }
        verify {
            reportPurchaseAttemptService.createReportPurchaseAttempt(
                userId,
                idempotencyKey,
                request,
                "1.0.0",
                ReportPurchaseOutcome.NOT_ENOUGH_MEALS,
            )
        }
    }

    @Test
    fun `코인 행 잠금을 제시간에 얻지 못하면 처리 중으로 응답하고 시도를 남긴다`() {
        givenTransactionThrows(CannotAcquireLockException("lock wait timeout"))

        assertBusinessException(ReportErrorCode.PURCHASE_IN_PROGRESS) { purchase() }
        verify {
            reportPurchaseAttemptService.createReportPurchaseAttempt(
                userId,
                idempotencyKey,
                request,
                "1.0.0",
                ReportPurchaseOutcome.PURCHASE_IN_PROGRESS,
            )
        }
    }

    @Test
    fun `시도를 남기지 못해도 거부 사유를 그대로 응답한다`() {
        givenTransactionThrows(BusinessException(ReportErrorCode.DUPLICATE_WEEK))
        every {
            reportPurchaseAttemptService.createReportPurchaseAttempt(any(), any(), any(), any(), any(), any())
        } throws DataAccessResourceFailureException("db down")

        assertBusinessException(ReportErrorCode.DUPLICATE_WEEK) { purchase() }
    }
}

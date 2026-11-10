package com.dandi.nyummy.report.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.report.dto.CreateReportPurchaseRequest
import com.dandi.nyummy.report.dto.ReportPurchaseResponse
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
import kotlin.test.assertSame

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

    private fun givenFailure(failure: Throwable) {
        every { reportPurchaseTransactionService.createReportPurchase(userId, idempotencyKey, request, "1.0.0") } throws
            failure
    }

    private fun verifyFailedAttempt(outcome: ReportPurchaseOutcome) {
        verify(exactly = 1) {
            reportPurchaseAttemptService.createReportPurchaseAttempt(userId, idempotencyKey, request, "1.0.0", outcome)
        }
    }

    @Test
    fun `구매 결과를 그대로 돌려주고 시도 기록은 트랜잭션에 맡긴다`() {
        val response = mockk<ReportPurchaseResponse>()
        every {
            reportPurchaseTransactionService.createReportPurchase(userId, idempotencyKey, request, "1.0.0")
        } returns
            response

        assertSame(response, purchase())
        verify(exactly = 0) {
            reportPurchaseAttemptService.createReportPurchaseAttempt(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `거부되면 그 사유를 기록하고 같은 사유로 응답한다`() {
        givenFailure(BusinessException(ReportErrorCode.NOT_ENOUGH_MEALS))

        assertBusinessException(ReportErrorCode.NOT_ENOUGH_MEALS) { purchase() }
        verifyFailedAttempt(ReportPurchaseOutcome.NOT_ENOUGH_MEALS)
    }

    @Test
    fun `구매 시도의 거부 사유가 아니면 예상 못 한 오류로 기록하고 그대로 응답한다`() {
        givenFailure(BusinessException(UserErrorCode.USER_NOT_FOUND))

        assertBusinessException(UserErrorCode.USER_NOT_FOUND) { purchase() }
        verifyFailedAttempt(ReportPurchaseOutcome.UNEXPECTED_ERROR)
    }

    @Test
    fun `잠금을 얻지 못하면 처리 중으로 기록하고 처리 중으로 응답한다`() {
        givenFailure(CannotAcquireLockException("Lock wait timeout exceeded"))

        assertBusinessException(ReportErrorCode.PURCHASE_IN_PROGRESS) { purchase() }
        verifyFailedAttempt(ReportPurchaseOutcome.PURCHASE_IN_PROGRESS)
    }

    @Test
    fun `거부 사유가 아닌 예외는 예상 못 한 오류로 기록하고 같은 예외를 그대로 던진다`() {
        val failure = IllegalStateException("가입 시 생성되어야 할 코인 행이 없습니다: userId=$userId")
        givenFailure(failure)

        val thrown = assertFailsWith<IllegalStateException> { purchase() }

        assertSame(failure, thrown)
        verifyFailedAttempt(ReportPurchaseOutcome.UNEXPECTED_ERROR)
    }

    @Test
    fun `DB 장애로 구매와 기록이 모두 실패해도 구매의 예외를 그대로 던진다`() {
        val failure = DataAccessResourceFailureException("구매 중 DB 연결 실패")
        givenFailure(failure)
        every {
            reportPurchaseAttemptService.createReportPurchaseAttempt(any(), any(), any(), any(), any(), any())
        } throws DataAccessResourceFailureException("기록 중 DB 연결 실패")

        val thrown = assertFailsWith<DataAccessResourceFailureException> { purchase() }

        assertSame(failure, thrown)
        verifyFailedAttempt(ReportPurchaseOutcome.UNEXPECTED_ERROR)
    }

    @Test
    fun `거부 기록에 실패해도 거부 사유로 응답한다`() {
        givenFailure(BusinessException(ReportErrorCode.DUPLICATE_WEEK))
        every {
            reportPurchaseAttemptService.createReportPurchaseAttempt(any(), any(), any(), any(), any(), any())
        } throws DataAccessResourceFailureException("DB 연결 실패")

        assertBusinessException(ReportErrorCode.DUPLICATE_WEEK) { purchase() }
    }

    @Test
    fun `거부 사유마다 기록할 결과가 있다`() {
        listOf(
            ReportErrorCode.IDEMPOTENCY_KEY_REUSED to ReportPurchaseOutcome.IDEMPOTENCY_KEY_REUSED,
            ReportErrorCode.INVALID_WEEK to ReportPurchaseOutcome.INVALID_WEEK,
            ReportErrorCode.NOT_ENOUGH_MEALS to ReportPurchaseOutcome.NOT_ENOUGH_MEALS,
            ReportErrorCode.DUPLICATE_WEEK to ReportPurchaseOutcome.DUPLICATE_WEEK,
            ReportErrorCode.PRICE_CHANGED to ReportPurchaseOutcome.PRICE_CHANGED,
            ReportErrorCode.INSUFFICIENT_BALANCE to ReportPurchaseOutcome.INSUFFICIENT_BALANCE,
        ).forEach { (errorCode, outcome) ->
            assertEquals(outcome, ReportPurchaseOutcome.from(errorCode))
        }
    }
}

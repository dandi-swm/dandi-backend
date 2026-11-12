package com.dandi.nyummy.shop.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.ShopErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseProductRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseResponse
import com.dandi.nyummy.shop.enum.ItemPurchaseOutcome
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessResourceFailureException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ItemPurchaseServiceTest {

    private val userId = 1L
    private val idempotencyKey = "key-1"
    private val request = CreateItemPurchaseRequest(listOf(ItemPurchaseProductRequest(101, 1, 1000)))

    private val itemPurchaseTransactionService = mockk<ItemPurchaseTransactionService>()
    private val itemPurchaseAttemptService = mockk<ItemPurchaseAttemptService>()
    private val service = ItemPurchaseService(itemPurchaseTransactionService, itemPurchaseAttemptService)

    init {
        every {
            itemPurchaseAttemptService.createItemPurchaseAttempt(any(), any(), any(), any(), any(), any())
        } just runs
    }

    private fun givenFailure(failure: Throwable) {
        every {
            itemPurchaseTransactionService.createItemPurchase(userId, idempotencyKey, request, "1.0.0", Language.KO)
        } throws
            failure
    }

    private fun purchase(request: CreateItemPurchaseRequest = this.request) =
        service.createItemPurchase(userId, idempotencyKey, request, "1.0.0", Language.KO)

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun verifyFailedAttempt(outcome: ItemPurchaseOutcome) {
        verify(exactly = 1) {
            itemPurchaseAttemptService.createItemPurchaseAttempt(userId, idempotencyKey, request, "1.0.0", outcome)
        }
    }

    @Test
    fun `구매 결과를 그대로 돌려주고 시도 기록은 트랜잭션에 맡긴다`() {
        val response = ItemPurchaseResponse(500, 1, 1000, 9000, 0)
        every {
            itemPurchaseTransactionService.createItemPurchase(userId, idempotencyKey, request, "1.0.0", Language.KO)
        } returns
            response

        assertEquals(response, purchase())
        verify(exactly = 0) {
            itemPurchaseAttemptService.createItemPurchaseAttempt(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `같은 상품을 두 번 보내면 트랜잭션을 열지 않고 거부한다`() {
        val duplicated = CreateItemPurchaseRequest(
            listOf(ItemPurchaseProductRequest(101, 1, 1000), ItemPurchaseProductRequest(101, 2, 1000)),
        )

        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) { purchase(duplicated) }
        verify(exactly = 0) { itemPurchaseTransactionService.createItemPurchase(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `거부되면 그 사유를 기록하고 같은 사유로 응답한다`() {
        givenFailure(BusinessException(ShopErrorCode.PRICE_CHANGED))

        assertBusinessException(ShopErrorCode.PRICE_CHANGED) { purchase() }
        verifyFailedAttempt(ItemPurchaseOutcome.PRICE_CHANGED)
    }

    @Test
    fun `구매 시도의 거부 사유가 아니면 예상 못 한 오류로 기록하고 그대로 응답한다`() {
        givenFailure(BusinessException(UserErrorCode.USER_NOT_FOUND))

        assertBusinessException(UserErrorCode.USER_NOT_FOUND) { purchase() }
        verifyFailedAttempt(ItemPurchaseOutcome.UNEXPECTED_ERROR)
    }

    @Test
    fun `잠금을 얻지 못하면 처리 중으로 기록하고 처리 중으로 응답한다`() {
        givenFailure(CannotAcquireLockException("Lock wait timeout exceeded"))

        assertBusinessException(ShopErrorCode.PURCHASE_IN_PROGRESS) { purchase() }
        verifyFailedAttempt(ItemPurchaseOutcome.PURCHASE_IN_PROGRESS)
    }

    @Test
    fun `거부 사유가 아닌 예외는 예상 못 한 오류로 기록하고 같은 예외를 그대로 던진다`() {
        val failure = IllegalStateException("가입 시 생성되어야 할 코인 행이 없습니다: userId=$userId")
        givenFailure(failure)

        val thrown = assertFailsWith<IllegalStateException> { purchase() }

        assertSame(failure, thrown)
        verifyFailedAttempt(ItemPurchaseOutcome.UNEXPECTED_ERROR)
    }

    @Test
    fun `DB 장애로 구매와 기록이 모두 실패해도 구매의 예외를 그대로 던진다`() {
        val failure = DataAccessResourceFailureException("구매 중 DB 연결 실패")
        givenFailure(failure)
        every {
            itemPurchaseAttemptService.createItemPurchaseAttempt(any(), any(), any(), any(), any(), any())
        } throws DataAccessResourceFailureException("기록 중 DB 연결 실패")

        val thrown = assertFailsWith<DataAccessResourceFailureException> { purchase() }

        assertSame(failure, thrown)
        verifyFailedAttempt(ItemPurchaseOutcome.UNEXPECTED_ERROR)
    }

    @Test
    fun `거부 기록에 실패해도 거부 사유로 응답한다`() {
        givenFailure(BusinessException(ShopErrorCode.PRICE_CHANGED))
        every {
            itemPurchaseAttemptService.createItemPurchaseAttempt(any(), any(), any(), any(), any(), any())
        } throws DataAccessResourceFailureException("DB 연결 실패")

        assertBusinessException(ShopErrorCode.PRICE_CHANGED) { purchase() }
    }

    @Test
    fun `거부 사유마다 기록할 결과가 있다`() {
        listOf(
            ShopErrorCode.IDEMPOTENCY_KEY_REUSED to ItemPurchaseOutcome.IDEMPOTENCY_KEY_REUSED,
            ShopErrorCode.PRODUCT_NOT_FOUND to ItemPurchaseOutcome.PRODUCT_NOT_FOUND,
            ShopErrorCode.PRODUCT_NOT_FOR_SALE to ItemPurchaseOutcome.PRODUCT_NOT_FOR_SALE,
            ShopErrorCode.ALREADY_OWNED to ItemPurchaseOutcome.ALREADY_OWNED,
            ShopErrorCode.PURCHASE_LIMIT_EXCEEDED to ItemPurchaseOutcome.PURCHASE_LIMIT_EXCEEDED,
            ShopErrorCode.CART_MISMATCH to ItemPurchaseOutcome.CART_MISMATCH,
            ShopErrorCode.PRICE_CHANGED to ItemPurchaseOutcome.PRICE_CHANGED,
            ShopErrorCode.INSUFFICIENT_BALANCE to ItemPurchaseOutcome.INSUFFICIENT_BALANCE,
        ).forEach { (errorCode, outcome) ->
            assertEquals(outcome, ItemPurchaseOutcome.from(errorCode))
        }
    }
}

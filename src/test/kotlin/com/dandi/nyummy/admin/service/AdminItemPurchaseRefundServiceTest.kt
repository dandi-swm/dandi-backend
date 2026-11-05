package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.dto.CreateItemPurchaseRefundRequest
import com.dandi.nyummy.admin.dto.ItemPurchaseRefundResponse
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminItemPurchaseRefundServiceTest {

    private val itemPurchaseProductRepository = mockk<ItemPurchaseProductRepository>()
    private val transactionService = mockk<AdminItemPurchaseRefundTransactionService>()
    private val service = AdminItemPurchaseRefundService(itemPurchaseProductRepository, transactionService)

    private val request = CreateItemPurchaseRefundRequest(listOf(1L), "실수 구매", expectedCoinQuantity = 500)

    private fun assertBusinessException(errorCode: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException> { block() }
        assertEquals(errorCode, exception.errorCode)
    }

    @Test
    fun `구매 상품을 산 사용자를 찾아 환불 트랜잭션에 넘긴다`() {
        val response = ItemPurchaseRefundResponse(itemPurchaseRefundId = 30L, coinQuantity = 500, balance = 1500)
        every { itemPurchaseProductRepository.findUserIdById(10L) } returns 1L
        every { transactionService.createRefund(1L, 2L, 10L, request) } returns response

        assertEquals(response, service.createRefund(adminId = 2L, itemPurchaseProductId = 10L, request = request))
    }

    @Test
    fun `없는 구매 상품은 환불 트랜잭션을 열지 않고 거부한다`() {
        every { itemPurchaseProductRepository.findUserIdById(10L) } returns null

        assertBusinessException(AdminErrorCode.ITEM_PURCHASE_PRODUCT_NOT_FOUND) {
            service.createRefund(adminId = 2L, itemPurchaseProductId = 10L, request = request)
        }
        verify(exactly = 0) { transactionService.createRefund(any(), any(), any(), any()) }
    }
}

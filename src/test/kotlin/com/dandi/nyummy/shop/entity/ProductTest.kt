package com.dandi.nyummy.shop.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.room.enum.RoomItemCategory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 판매 기간 [saleStartsAt, saleEndsAt)의 규칙을 다룬다. */
class ProductTest {

    private val saleStartsAt = Instant.parse("2026-11-01T00:00:00Z")
    private val saleEndsAt = Instant.parse("2026-12-01T00:00:00Z")

    private fun product() = Product(
        category = RoomItemCategory.BED,
        name = "돔 침대",
        representativeItemId = 11,
        price = 650,
        isLimited = false,
        saleStartsAt = saleStartsAt,
        saleEndsAt = saleEndsAt,
    )

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `판매 기간을 바꾸면 시작과 종료 시각이 함께 바뀐다`() {
        val product = product()
        val newStartsAt = saleStartsAt.plusSeconds(60)
        val newEndsAt = saleEndsAt.plusSeconds(60)

        product.updateSalePeriod(newStartsAt, newEndsAt)

        assertEquals(newStartsAt, product.saleStartsAt)
        assertEquals(newEndsAt, product.saleEndsAt)
    }

    @Test
    fun `종료 시각을 비우면 종료일이 없어진다`() {
        val product = product()

        product.updateSalePeriod(saleStartsAt, null)

        assertNull(product.saleEndsAt)
    }

    @Test
    fun `종료 시각이 시작 시각과 같으면 거부하고 기간을 바꾸지 않는다`() {
        val product = product()

        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            product.updateSalePeriod(saleEndsAt, saleEndsAt)
        }
        assertEquals(saleStartsAt, product.saleStartsAt)
    }

    @Test
    fun `종료 시각이 시작 시각보다 앞이면 거부한다`() {
        assertBusinessException(CommonErrorCode.INVALID_INPUT_VALUE) {
            Product.validateSalePeriod(saleStartsAt, saleStartsAt.minusSeconds(1))
        }
    }
}

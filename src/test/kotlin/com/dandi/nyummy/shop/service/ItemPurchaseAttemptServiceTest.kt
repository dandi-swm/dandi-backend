package com.dandi.nyummy.shop.service

import com.dandi.nyummy.shop.dto.CreateItemPurchaseRequest
import com.dandi.nyummy.shop.dto.ItemPurchaseProductRequest
import com.dandi.nyummy.shop.entity.ItemPurchaseAttempt
import com.dandi.nyummy.shop.enum.ItemPurchaseOutcome
import com.dandi.nyummy.shop.repository.ItemPurchaseAttemptRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemPurchaseAttemptServiceTest {

    private val repository = mockk<ItemPurchaseAttemptRepository>()
    private val service = ItemPurchaseAttemptService(repository, JsonMapper.builder().build())
    private val saved = slot<ItemPurchaseAttempt>()

    init {
        every { repository.save(capture(saved)) } answers { firstArg() }
    }

    private fun request(quantity: Int = 2, purchasePrice: Int = 1000) =
        CreateItemPurchaseRequest(listOf(ItemPurchaseProductRequest(101, quantity, purchasePrice)))

    @Test
    fun `앱이 보낸 상품과 합계, 결과를 그대로 남긴다`() {
        service.createItemPurchaseAttempt(1L, "key", request(), "1.0.0", ItemPurchaseOutcome.SUCCEEDED, 500L)

        assertEquals(1L, saved.captured.userId)
        assertEquals("key", saved.captured.idempotencyKey)
        assertEquals(2000, saved.captured.requestedTotalPrice)
        assertEquals(ItemPurchaseOutcome.SUCCEEDED, saved.captured.outcome)
        assertEquals(500L, saved.captured.itemPurchaseId)
        assertEquals("1.0.0", saved.captured.appVersion)
        val products = JsonMapper.builder().build().readTree(saved.captured.requestedProducts)
        assertEquals(101L, products[0]["productId"].asLong())
        assertEquals(2, products[0]["quantity"].asInt())
        assertEquals(1000, products[0]["purchasePrice"].asInt())
    }

    @Test
    fun `합계가 Int 범위를 넘으면 최댓값으로 남긴다`() {
        service.createItemPurchaseAttempt(
            1L,
            "key",
            request(quantity = Int.MAX_VALUE, purchasePrice = Int.MAX_VALUE),
            null,
            ItemPurchaseOutcome.PRICE_CHANGED,
        )

        assertEquals(Int.MAX_VALUE, saved.captured.requestedTotalPrice)
    }

    @Test
    fun `앱 버전이 컬럼 길이를 넘으면 null로 남긴다`() {
        service.createItemPurchaseAttempt(1L, "key", request(), "1".repeat(21), ItemPurchaseOutcome.PRICE_CHANGED)

        assertNull(saved.captured.appVersion)
    }
}

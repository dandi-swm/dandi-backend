package com.dandi.nyummy.e2e.shop

import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 구매는 아직 저장하지 않고, 고정 상품·잔액으로 검증한 결과만 돌려준다.
// 저장을 붙이면 고정 계정의 코인과 장바구니가 줄어드므로, 구매 성공 테스트에 되돌리는 정리를 넣어야 한다.
class ItemPurchaseE2eTest {

    @Test
    fun `장바구니에서 선택한 상품을 사면 결제 정보대로 코인이 빠지고 같은 키로 다시 보내면 같은 구매를 받는다`() {
        val cart = getCart()
        val products = createPurchaseProducts(cart)

        @Suppress("UNCHECKED_CAST")
        val summary = cart.getValue("summary") as Map<String, Int>
        val idempotencyKey = UUID.randomUUID().toString()

        val itemPurchase = createItemPurchase(idempotencyKey, products)
        assertEquals(summary["quantity"], itemPurchase["quantity"])
        assertEquals(summary["purchasePrice"], itemPurchase["purchasePrice"])
        assertEquals(summary["balanceAfter"], itemPurchase["balance"])

        val retried = createItemPurchase(idempotencyKey, products)
        assertEquals(itemPurchase["itemPurchaseId"], retried["itemPurchaseId"], "같은 키 재시도가 다른 구매를 돌려줬습니다")
    }

    @Test
    fun `화면에서 본 가격이 지금 가격과 다르면 거부하고 장바구니는 그대로다`() {
        val products = createPurchaseProducts(getCart())
        val changedProducts = products.mapIndexed { index, product ->
            if (index == 0) product + ("purchasePrice" to (product.getValue("purchasePrice") as Int) + 1) else product
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(mapOf("products" to changedProducts))
        } When {
            post("/api/v1/shop/purchases")
        } Then {
            validateError(409, "api.shop.priceChanged")
        }

        val cartProductIds = getCart().cartItems().map { it.productId() }
        assertTrue(cartProductIds.containsAll(products.map { it["productId"] }), "거부된 구매가 장바구니를 바꿨습니다")
    }

    @Test
    fun `Idempotency-Key 없이 사거나 상품 없이 사면 거부한다`() {
        val products = createPurchaseProducts(getCart())

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("products" to products))
        } When {
            post("/api/v1/shop/purchases")
        } Then {
            validateError(400, "api.common.invalidInputValue")
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(mapOf("products" to emptyList<Map<String, Any>>()))
        } When {
            post("/api/v1/shop/purchases")
        } Then {
            validateError(400, "api.common.invalidInputValue")
        }
    }

    private fun createItemPurchase(idempotencyKey: String, products: List<Map<String, Any>>): Map<String, Any?> =
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            header("Idempotency-Key", idempotencyKey)
            body(mapOf("products" to products))
        } When {
            post("/api/v1/shop/purchases")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("itemPurchaseId", "quantity", "purchasePrice", "balance", "cartItemCount"))
        } Extract {
            path("")
        }

    private fun getCart(): Map<String, Any?> = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v1/shop/cart")
    } Then {
        statusCode(200)
    } Extract {
        path("")
    }

    // 앱처럼 장바구니에서 선택된 상품을 담긴 수량과 화면에 보인 1개 결제가로 보낸다
    private fun createPurchaseProducts(cart: Map<String, Any?>): List<Map<String, Any>> {
        val selectedItems = cart.cartItems().filter { it["isSelected"] == true }
        assertNotNull(selectedItems.firstOrNull(), "고정 계정 장바구니에 선택된 상품이 없습니다")
        return selectedItems.map {
            mapOf(
                "productId" to it.productId(),
                "quantity" to it.getValue("quantity") as Int,
                "purchasePrice" to it.getValue("purchasePrice") as Int,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.cartItems(): List<Map<String, Any?>> =
        getValue("cartItems") as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.productId(): Long =
        ((getValue("shopItem") as Map<String, Any?>).getValue("productId") as Number).toLong()

    private fun ValidatableResponse.validateError(status: Int, code: String) {
        statusCode(status)
        body("code", equalTo(code))
    }
}

package com.dandi.nyummy.e2e.shop

import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// 장바구니는 아직 저장하지 않고, 요청마다 고정 장바구니에 그 요청을 반영한 결과만 돌려준다.
// 저장을 붙이면 담기·수량 변경·선택 변경·빼기 테스트에 finally로 원래 장바구니를 되돌리는 정리를 넣어야 한다.
class CartE2eTest {

    @Smoke
    @Test
    fun `장바구니를 조회하면 결제 정보가 선택된 항목의 합계와 맞다`() {
        val cart = getCart()

        assertSummaryMatchesItems(cart)
    }

    @Test
    fun `장바구니 수량을 바꾸면 그 수량이 반영된 장바구니 전체를 받는다`() {
        val productId = findPurchasableProductId(getCart())

        val cart: Map<String, Any?> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("quantity" to 1))
        } When {
            put("/api/v1/shop/cart/items/$productId")
        } Then {
            statusCode(200)
        } Extract {
            path("")
        }

        val cartItem = cart.cartItems().first { it.productId() == productId }
        assertEquals(1, cartItem["quantity"])
        assertSummaryMatchesItems(cart)
    }

    @Test
    fun `장바구니에 없는 상품의 수량을 바꾸거나 0개로 바꾸면 거부한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("quantity" to 1))
        } When {
            put("/api/v1/shop/cart/items/${Long.MAX_VALUE}")
        } Then {
            validateError(404, "api.shop.cartItemNotFound")
        }

        val productId = findPurchasableProductId(getCart())
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("quantity" to 0))
        } When {
            put("/api/v1/shop/cart/items/$productId")
        } Then {
            validateError(400, "api.common.invalidInputValue")
        }
    }

    @Test
    fun `선택을 모두 해제하면 결제 정보가 0이 된다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("productIds" to emptyList<Long>()))
        } When {
            put("/api/v1/shop/cart/selection")
        } Then {
            statusCode(200)
            body("selectedItemCount", equalTo(0))
            body("summary.quantity", equalTo(0))
            body("summary.purchasePrice", equalTo(0))
        }
    }

    @Test
    fun `상품을 빼면 그 상품이 빠진 장바구니 전체를 받고 productIds가 없으면 거부한다`() {
        val productId = findPurchasableProductId(getCart())

        val cart: Map<String, Any?> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParam("productIds", productId)
        } When {
            delete("/api/v1/shop/cart/items")
        } Then {
            statusCode(200)
        } Extract {
            path("")
        }
        assertEquals(null, cart.cartItems().find { it.productId() == productId }, "뺀 상품이 장바구니에 남아 있습니다")
        assertSummaryMatchesItems(cart)

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            delete("/api/v1/shop/cart/items")
        } Then {
            validateError(400, "api.common.invalidInputValue")
        }
    }

    @Test
    fun `상품을 담으면 담은 뒤 수량과 장바구니 상품 종류 수를 받는다`() {
        val productId: Long = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParam("tab", "INTERIOR")
        } When {
            get("/api/v1/shop/products")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getLong("items[0].productId")
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("productId" to productId, "quantity" to 1))
        } When {
            post("/api/v1/shop/cart/items")
        } Then {
            statusCode(200)
            body("productId", equalTo(productId.toInt()))
            body("keySet()", hasItems("productId", "quantity", "cartItemCount"))
        }
    }

    @Test
    fun `없는 상품을 담거나 0개를 담으면 거부한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("productId" to Long.MAX_VALUE, "quantity" to 1))
        } When {
            post("/api/v1/shop/cart/items")
        } Then {
            validateError(404, "api.shop.productNotFound")
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("productId" to Long.MAX_VALUE, "quantity" to 0))
        } When {
            post("/api/v1/shop/cart/items")
        } Then {
            validateError(400, "api.common.invalidInputValue")
        }
    }

    private fun getCart(): Map<String, Any?> = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v1/shop/cart")
    } Then {
        statusCode(200)
        body("keySet()", hasItems("cartItems", "selectedItemCount", "summary"))
        body(
            "summary.keySet()",
            hasItems("quantity", "listPrice", "discountPrice", "purchasePrice", "balance", "balanceAfter"),
        )
    } Extract {
        path("")
    }

    private fun findPurchasableProductId(cart: Map<String, Any?>): Long = assertNotNull(
        cart.cartItems().firstOrNull { it["canPurchase"] == true },
        "고정 계정 장바구니에 구매 가능한 상품이 없습니다",
    ).productId()

    // 결제 정보는 선택됐고 구매 가능한 항목만 합친다. 앱은 이 값을 그대로 그리므로 항목과 어긋나면 안 된다.
    private fun assertSummaryMatchesItems(cart: Map<String, Any?>) {
        val selectedItems = cart.cartItems().filter { it["isSelected"] == true }

        @Suppress("UNCHECKED_CAST")
        val summary = cart.getValue("summary") as Map<String, Int>

        assertEquals(selectedItems.size, cart["selectedItemCount"])
        assertEquals(selectedItems.sumOf { it["quantity"] as Int }, summary["quantity"])
        assertEquals(
            selectedItems.sumOf {
                (it["purchasePrice"] as Int) * (it["quantity"] as Int)
            },
            summary["purchasePrice"],
        )
        assertEquals(summary.getValue("listPrice") - summary.getValue("purchasePrice"), summary["discountPrice"])
        assertEquals(summary.getValue("balance") - summary.getValue("purchasePrice"), summary["balanceAfter"])
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

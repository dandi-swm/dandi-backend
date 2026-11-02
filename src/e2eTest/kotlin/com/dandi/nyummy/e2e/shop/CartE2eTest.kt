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
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 장바구니는 고정 계정에 저장된다. 바꾸는 테스트는 장바구니에 없던 상품을 담아 쓰고, finally에서 빼서 원래 장바구니로 되돌린다.
// 선택을 바꾸는 테스트는 원래 선택 상태도 되돌린다. 바꾸는 테스트는 prod에 데이터를 남기므로 @Smoke를 붙이지 않는다.
class CartE2eTest {

    @Smoke
    @Test
    fun `장바구니를 조회하면 결제 정보가 선택된 항목의 합계와 맞다`() {
        val cart = getCart()

        assertSummaryMatchesItems(cart)
    }

    @Test
    fun `같은 상품을 두 번 담으면 한 항목의 수량이 늘고 선택 상태로 담긴다`() {
        val before = getCart()
        val productId = getProductIdNotInCart(before)

        try {
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("productId" to productId, "quantity" to 1))
            } When {
                post("/api/v1/shop/cart/items")
            } Then {
                statusCode(200)
                body("productId", equalTo(productId.toInt()))
                body("quantity", equalTo(1))
                body("cartItemCount", equalTo(before.cartItems().size + 1))
            }

            // 이미 담긴 상품이면 새 항목을 만들지 않고 수량만 더한다
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("productId" to productId, "quantity" to 1))
            } When {
                post("/api/v1/shop/cart/items")
            } Then {
                statusCode(200)
                body("quantity", equalTo(2))
                body("cartItemCount", equalTo(before.cartItems().size + 1))
            }

            val cart = getCart()
            val cartItem = cart.cartItems().single { it.productId() == productId }
            assertEquals(2, cartItem["quantity"])
            assertEquals(true, cartItem["isSelected"])
            assertSummaryMatchesItems(cart)
        } finally {
            deleteCartItemsQuietly(productId)
        }
    }

    @Test
    fun `없는 상품을 담거나 0개를 담으면 거부하고 장바구니는 그대로다`() {
        val before = getCart()

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

        assertEquals(before.cartItemQuantities(), getCart().cartItemQuantities(), "거부된 담기가 장바구니를 바꿨습니다")
    }

    @Test
    fun `장바구니 수량을 바꾸면 그 수량이 반영된 장바구니 전체를 받는다`() {
        val productId = getProductIdNotInCart(getCart())

        try {
            createCartItem(productId, quantity = 1)

            val cart: Map<String, Any?> = Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("quantity" to 3))
            } When {
                put("/api/v1/shop/cart/items/$productId")
            } Then {
                statusCode(200)
            } Extract {
                path("")
            }

            assertEquals(3, cart.cartItems().single { it.productId() == productId }["quantity"])
            assertSummaryMatchesItems(cart)
        } finally {
            deleteCartItemsQuietly(productId)
        }
    }

    @Test
    fun `장바구니에 없는 상품의 수량을 바꾸거나 0개로 바꾸면 거부하고 수량은 그대로다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("quantity" to 1))
        } When {
            put("/api/v1/shop/cart/items/${Long.MAX_VALUE}")
        } Then {
            validateError(404, "api.shop.cartItemNotFound")
        }

        val productId = getProductIdNotInCart(getCart())
        try {
            createCartItem(productId, quantity = 1)

            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("quantity" to 0))
            } When {
                put("/api/v1/shop/cart/items/$productId")
            } Then {
                validateError(400, "api.common.invalidInputValue")
            }

            assertEquals(1, getCart().cartItems().single { it.productId() == productId }["quantity"])
        } finally {
            deleteCartItemsQuietly(productId)
        }
    }

    @Test
    fun `선택을 요청 목록으로 맞추면 목록에 없는 항목은 해제된다`() {
        val before = getCart()
        val selectedProductIds = before.cartItems().filter { it["isSelected"] == true }.map { it.productId() }
        val productId = getProductIdNotInCart(before)

        try {
            createCartItem(productId, quantity = 1)

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

            val cart = updateCartSelection(listOf(productId))
            val selectedItems = cart.cartItems().filter { it["isSelected"] == true }
            assertEquals(listOf(productId), selectedItems.map { it.productId() })
            assertSummaryMatchesItems(cart)
        } finally {
            deleteCartItemsQuietly(productId)
            updateCartSelection(selectedProductIds)
        }
    }

    @Test
    fun `상품을 빼면 그 상품이 빠진 장바구니 전체를 받고 productIds가 없으면 거부한다`() {
        val productId = getProductIdNotInCart(getCart())

        try {
            createCartItem(productId, quantity = 1)

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
            assertNull(cart.cartItems().find { it.productId() == productId }, "뺀 상품이 장바구니에 남아 있습니다")
            assertSummaryMatchesItems(cart)

            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                delete("/api/v1/shop/cart/items")
            } Then {
                validateError(400, "api.common.invalidInputValue")
            }
        } finally {
            deleteCartItemsQuietly(productId)
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

    // 고정 계정 장바구니에 없는 인테리어 상품. 방 배경은 이미 가졌을 수 있어 고르지 않는다.
    // 판매 중인 상품이 없는 환경에서는 검증할 수 없으니 실패가 아니라 건너뛴다.
    private fun getProductIdNotInCart(cart: Map<String, Any?>): Long {
        val cartProductIds = cart.cartItems().map { it.productId() }.toSet()
        val productIds: List<Number> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("tab" to "INTERIOR", "size" to 50))
        } When {
            get("/api/v1/shop/products")
        } Then {
            statusCode(200)
        } Extract {
            path("items.productId")
        }

        return productIds.map { it.toLong() }.firstOrNull { it !in cartProductIds }
            ?: abort("장바구니에 없는 판매 중 인테리어 상품이 없어 건너뜁니다")
    }

    private fun createCartItem(productId: Long, quantity: Int) {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("productId" to productId, "quantity" to quantity))
        } When {
            post("/api/v1/shop/cart/items")
        } Then {
            statusCode(200)
        }
    }

    private fun updateCartSelection(productIds: List<Long>): Map<String, Any?> = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        body(mapOf("productIds" to productIds))
    } When {
        put("/api/v1/shop/cart/selection")
    } Then {
        statusCode(200)
    } Extract {
        path("")
    }

    // 정리용. 이미 빠졌을 수 있으니 결과를 검증하지 않는다.
    private fun deleteCartItemsQuietly(productId: Long) {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParam("productIds", productId)
        } When {
            delete("/api/v1/shop/cart/items")
        }
    }

    // 결제 정보는 선택됐고 구매 가능한 항목만 합친다. 앱은 이 값을 그대로 그리므로 항목과 어긋나면 안 된다.
    private fun assertSummaryMatchesItems(cart: Map<String, Any?>) {
        val selectedItems = cart.cartItems().filter { it["isSelected"] == true }

        @Suppress("UNCHECKED_CAST")
        val summary = cart.getValue("summary") as Map<String, Int>

        assertTrue(selectedItems.all { it["canPurchase"] == true }, "구매 불가 항목이 선택돼 있습니다")
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

    private fun Map<String, Any?>.cartItemQuantities(): Map<Long, Any?> =
        cartItems().associate { it.productId() to it["quantity"] }

    private fun ValidatableResponse.validateError(status: Int, code: String) {
        statusCode(status)
        body("code", equalTo(code))
    }
}

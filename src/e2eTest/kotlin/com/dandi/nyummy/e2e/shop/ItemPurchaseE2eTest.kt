package com.dandi.nyummy.e2e.shop

import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// 구매 성공은 고정 계정의 코인과 구매 이력을 되돌릴 수 없다. 환불은 어드민 API라 배포 서버에 HTTP로 닿지 않는다.
// 그래서 @Smoke를 붙이지 않아 dev에서만 돌고, 가장 싼 상품을 사서 코인을 적게 쓰며, 산 아이템·우편·장바구니만 되돌린다.
class ItemPurchaseE2eTest {

    @Test
    fun `가장 싼 상품을 사면 코인이 빠지고 우편으로 받으며 같은 키로 다시 보내면 같은 구매를 받는다`() {
        val product = createCheapestCartItem()
        val idempotencyKey = UUID.randomUUID().toString()

        try {
            val balanceBefore = getBalance()
            val ownedCountBefore = getOwnedCount(product.productId)
            if (product.purchasePrice > balanceBefore) {
                abort<Unit>("고정 계정의 코인이 ${product.purchasePrice}보다 적어 건너뜁니다: $balanceBefore")
            }

            // 1. 구매: 코인이 빠지고 산 상품이 장바구니에서 빠진다
            val itemPurchase = createItemPurchase(idempotencyKey, product.toRequestProducts())
            assertEquals(1, itemPurchase["quantity"])
            assertEquals(product.purchasePrice, itemPurchase["purchasePrice"])
            assertEquals(balanceBefore - product.purchasePrice, itemPurchase["balance"])
            assertEquals(balanceBefore - product.purchasePrice, getBalance(), "구매 뒤 잔액")
            assertNull(getCart().findCartItem(product.productId), "산 상품이 장바구니에 남아 있습니다")
            assertEquals(ownedCountBefore + 1, getOwnedCount(product.productId), "우편을 받기 전 상품 카드의 보유 개수")
            validateLatestCoinHistory(product, balanceAfter = balanceBefore - product.purchasePrice)

            // 2. 같은 키 재시도: 코인이 더 빠지지 않고 같은 구매를 받는다
            val retried = createItemPurchase(idempotencyKey, product.toRequestProducts())
            assertEquals(itemPurchase["itemPurchaseId"], retried["itemPurchaseId"], "같은 키 재시도가 다른 구매를 돌려줬습니다")
            assertEquals(balanceBefore - product.purchasePrice, getBalance(), "같은 키 재시도 뒤 잔액")

            // 3. 같은 키로 다른 구매: 거부하고 코인이 그대로다
            postItemPurchase(idempotencyKey, listOf(product.toRequestProduct(quantity = 2))) Then {
                validateError(409, "api.shop.idempotencyKeyReused")
            }
            assertEquals(balanceBefore - product.purchasePrice, getBalance(), "거부된 구매 뒤 잔액")

            // 4. 우편 받기: 산 아이템이 우편함에 오고, 받으면 보유 아이템이 된다
            val mailId = getUnclaimedMailId(product.name)
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                post("/api/v1/mails/$mailId/claim")
            } Then {
                statusCode(200)
                body("items[0].imageId", equalTo(product.itemId.toInt()))
                body("coin", equalTo(null))
            }

            assertEquals(ownedCountBefore + 1, getOwnedCount(product.productId), "우편을 받은 뒤 상품 카드의 보유 개수")

            // 5. 정리: 코인과 구매 이력은 되돌릴 수 없어 남고, 보유 아이템과 우편만 지운다
            deleteLatestOwnedRoomItem(product)
            Given { spec(RequestSpecs.createAuthorizedSpec()) } When { delete("/api/v1/mails/$mailId") }
        } finally {
            deleteCartItemsQuietly(product.productId)
        }
    }

    @Test
    fun `화면에서 본 가격이 지금 가격과 다르면 거부하고 장바구니와 잔액은 그대로다`() {
        val product = createCheapestCartItem()

        try {
            val balanceBefore = getBalance()
            val changedProducts = listOf(product.toRequestProduct(purchasePrice = product.purchasePrice + 1))

            postItemPurchase(UUID.randomUUID().toString(), changedProducts) Then {
                validateError(409, "api.shop.priceChanged")
            }

            assertNotNull(getCart().findCartItem(product.productId), "거부된 구매가 장바구니를 바꿨습니다")
            assertEquals(balanceBefore, getBalance(), "거부된 구매가 잔액을 바꿨습니다")
        } finally {
            deleteCartItemsQuietly(product.productId)
        }
    }

    @Test
    fun `장바구니에 담긴 수량과 다르면 거부하고 장바구니와 잔액은 그대로다`() {
        val product = createCheapestCartItem()

        try {
            val balanceBefore = getBalance()

            postItemPurchase(UUID.randomUUID().toString(), listOf(product.toRequestProduct(quantity = 2))) Then {
                validateError(409, "api.shop.cartMismatch")
            }

            assertEquals(1, getCart().findCartItem(product.productId)?.get("quantity"), "거부된 구매가 수량을 바꿨습니다")
            assertEquals(balanceBefore, getBalance(), "거부된 구매가 잔액을 바꿨습니다")
        } finally {
            deleteCartItemsQuietly(product.productId)
        }
    }

    @Test
    fun `없는 상품을 사면 거부하고 잔액은 그대로다`() {
        val balanceBefore = getBalance()
        val products = listOf(mapOf("productId" to Long.MAX_VALUE, "quantity" to 1, "purchasePrice" to 0))

        postItemPurchase(UUID.randomUUID().toString(), products) Then {
            validateError(404, "api.shop.productNotFound")
        }

        assertEquals(balanceBefore, getBalance(), "거부된 구매가 잔액을 바꿨습니다")
    }

    @Test
    fun `Idempotency-Key 없이 사거나 상품 없이 사거나 같은 상품을 두 번 보내면 거부한다`() {
        val balanceBefore = getBalance()
        val requestProduct = mapOf("productId" to Long.MAX_VALUE, "quantity" to 1, "purchasePrice" to 0)

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("products" to listOf(requestProduct)))
        } When {
            post("/api/v1/shop/purchases")
        } Then {
            validateError(400, "api.common.invalidInputValue")
        }

        postItemPurchase(UUID.randomUUID().toString(), emptyList()) Then {
            validateError(400, "api.common.invalidInputValue")
        }

        postItemPurchase(UUID.randomUUID().toString(), listOf(requestProduct, requestProduct)) Then {
            validateError(400, "api.common.invalidInputValue")
        }

        assertEquals(balanceBefore, getBalance(), "거부된 구매가 잔액을 바꿨습니다")
    }

    private fun postItemPurchase(idempotencyKey: String, products: List<Map<String, Any>>) = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        header("Idempotency-Key", idempotencyKey)
        body(mapOf("products" to products))
    } When {
        post("/api/v1/shop/purchases")
    }

    private fun createItemPurchase(idempotencyKey: String, products: List<Map<String, Any>>): Map<String, Any?> =
        postItemPurchase(idempotencyKey, products) Then {
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

    private fun getBalance(): Int {
        @Suppress("UNCHECKED_CAST")
        val summary = getCart().getValue("summary") as Map<String, Int>
        return summary.getValue("balance")
    }

    /**
     * 장바구니에 없는 가장 싼 상품을 1개 담고 그 상품을 돌려준다. 끝나면 호출한 쪽이 장바구니에서 뺀다.
     *
     * 방 배경은 한 번 사면 다시 살 수 없고, 한정 판매와 구매 한도가 1개인 상품은 금세 살 수 없게 되므로 고르지 않는다.
     * 한도가 남았는지는 담아 봐야 알 수 있어 담을 수 있는 수량(maxQuantity)으로 거른다.
     * 살 수 있는 상품이 없는 환경에서는 검증할 수 없으니 실패가 아니라 건너뛴다.
     */
    private fun createCheapestCartItem(): CartProduct {
        val cartProductIds = getCart().cartItems().map { it.productId() }.toSet()
        val candidates: List<Map<String, Any?>> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("tab" to "INTERIOR", "sort" to "PRICE_ASC", "size" to 50))
        } When {
            get("/api/v1/shop/products")
        } Then {
            statusCode(200)
        } Extract {
            path("items")
        }

        candidates
            .filter { it["category"] != "BACKGROUND" && it["isLimited"] == false }
            .filter { (it.getValue("productId") as Number).toLong() !in cartProductIds }
            .forEach { candidate ->
                val productId = (candidate.getValue("productId") as Number).toLong()
                val statusCode = Given {
                    spec(RequestSpecs.createAuthorizedSpec())
                    body(mapOf("productId" to productId, "quantity" to 1))
                } When {
                    post("/api/v1/shop/cart/items")
                } Extract {
                    statusCode()
                }
                if (statusCode != 200) return@forEach

                val cartItem = getCart().findCartItem(productId)
                if (cartItem?.get("canPurchase") == true && (cartItem["maxQuantity"] as Int) > 1) {
                    return CartProduct(
                        productId = productId,
                        itemId = (candidate.getValue("itemId") as Number).toLong(),
                        name = candidate.getValue("name") as String,
                        category = candidate.getValue("category") as String,
                        purchasePrice = cartItem.getValue("purchasePrice") as Int,
                    )
                }
                deleteCartItemsQuietly(productId)
            }

        abort<Unit>("살 수 있는 판매 중 인테리어 상품이 없어 건너뜁니다")
        error("abort는 항상 예외를 던진다")
    }

    // 0코인 상품이면 내역이 생기지 않아 검증할 수 없다
    private fun validateLatestCoinHistory(product: CartProduct, balanceAfter: Int) {
        if (product.purchasePrice == 0) return

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParam("size", 1)
        } When {
            get("/api/v1/coins/histories")
        } Then {
            statusCode(200)
            body("coinHistories[0].category", equalTo("ROOM_ITEM_PURCHASE"))
            body("coinHistories[0].title", equalTo("${product.name} 구매"))
            body("coinHistories[0].quantity", equalTo(-product.purchasePrice))
            body("coinHistories[0].balanceAfter", equalTo(balanceAfter))
        }
    }

    private fun getOwnedCount(productId: Long): Int {
        val products: List<Map<String, Any?>> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("tab" to "INTERIOR", "sort" to "PRICE_ASC", "size" to 50))
        } When {
            get("/api/v1/shop/products")
        } Then {
            statusCode(200)
        } Extract {
            path("items")
        }
        val product = assertNotNull(
            products.firstOrNull { (it.getValue("productId") as Number).toLong() == productId },
            "상품 목록에 상품이 없습니다: $productId",
        )
        return product.getValue("ownedCount") as Int
    }

    // 다른 테스트의 우편과 겹치지 않게 상품 이름으로 찾는다
    private fun getUnclaimedMailId(title: String): Long {
        val mails: List<Map<String, Any?>> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/mails")
        } Then {
            statusCode(200)
        } Extract {
            path("mails")
        }
        val mail = assertNotNull(
            mails.firstOrNull { it["category"] == "ITEM" && it["title"] == title && it["isClaimed"] == false },
            "산 상품의 우편이 우편함에 없습니다: $title",
        )
        return (mail.getValue("mailId") as Number).toLong()
    }

    // 방금 받은 아이템을 지운다. 최신순이라 같은 아이템 중 장착하지 않은 첫 번째가 방금 받은 것이다.
    private fun deleteLatestOwnedRoomItem(product: CartProduct) {
        val ownedRoomItems: List<Map<String, Any?>> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("category" to product.category, "size" to 50))
        } When {
            get("/api/v1/rooms/owned-items")
        } Then {
            statusCode(200)
        } Extract {
            path("ownedRoomItems")
        }
        val ownedRoomItem = assertNotNull(
            ownedRoomItems.firstOrNull {
                (it["itemId"] as Number).toLong() == product.itemId &&
                    it["isEquipped"] == false
            },
            "받은 아이템이 보유 아이템에 없습니다: ${product.name}",
        )

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            delete("/api/v1/rooms/owned-items/${ownedRoomItem["ownedRoomItemId"]}")
        }
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

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.cartItems(): List<Map<String, Any?>> =
        getValue("cartItems") as List<Map<String, Any?>>

    private fun Map<String, Any?>.findCartItem(productId: Long): Map<String, Any?>? =
        cartItems().find { it.productId() == productId }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.productId(): Long =
        ((getValue("shopItem") as Map<String, Any?>).getValue("productId") as Number).toLong()

    private fun ValidatableResponse.validateError(status: Int, code: String) {
        statusCode(status)
        body("code", equalTo(code))
    }

    private data class CartProduct(
        val productId: Long,
        val itemId: Long,
        val name: String,
        val category: String,
        val purchasePrice: Int,
    ) {
        fun toRequestProducts(): List<Map<String, Any>> = listOf(toRequestProduct())

        fun toRequestProduct(quantity: Int = 1, purchasePrice: Int = this.purchasePrice): Map<String, Any> =
            mapOf("productId" to productId, "quantity" to quantity, "purchasePrice" to purchasePrice)
    }
}

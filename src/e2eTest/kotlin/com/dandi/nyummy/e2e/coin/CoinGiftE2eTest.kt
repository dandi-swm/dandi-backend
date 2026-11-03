package com.dandi.nyummy.e2e.coin

import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// 받기를 실제로 저장하면 prod에 코인이 남으므로 @Smoke를 붙이지 않는다.
// 고정 계정은 한 번만 코인을 받으므로 잔액 값은 보지 않고 구조와 선물 코인 수만 본다.
class CoinGiftE2eTest {

    @Test
    fun `어떤 그룹의 패키지를 골라도 3000코인을 받는다`() {
        listOf("A", "B").forEach { variant ->
            postCoinGift(variant, mapOf("coinPackageId" to 3)) Then {
                statusCode(200)
                body("coinQuantity", equalTo(3000))
                body("balance", instanceOf<Any>(Number::class.java))
            }
        }
    }

    @Test
    fun `그룹이나 패키지가 잘못되면 거부하고 잔액은 그대로다`() {
        val balanceBefore = getBalance()

        listOf(null, "C").forEach { variant ->
            postCoinGift(variant, mapOf("coinPackageId" to 1)) Then {
                validateError(400, "api.common.invalidInputValue")
            }
        }
        listOf(0, 4).forEach { coinPackageId ->
            postCoinGift("A", mapOf("coinPackageId" to coinPackageId)) Then {
                validateError(404, "api.coin.coinPackageNotFound")
            }
        }
        postCoinGift("A", emptyMap()) Then {
            validateError(400, "api.common.invalidInputValue")
        }

        assertEquals(balanceBefore, getBalance(), "거부된 요청이 잔액을 바꿨습니다")
    }

    private fun postCoinGift(variant: String?, requestBody: Map<String, Any>) = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        variant?.let { header("X-Coin-Package-Variant", it) }
        body(requestBody)
    } When {
        post("/api/v1/coins/gifts")
    }

    private fun getBalance(): Int = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v1/shop/me")
    } Then {
        statusCode(200)
    } Extract {
        path("balance")
    }

    private fun ValidatableResponse.validateError(status: Int, code: String) {
        statusCode(status)
        body("code", equalTo(code))
    }
}

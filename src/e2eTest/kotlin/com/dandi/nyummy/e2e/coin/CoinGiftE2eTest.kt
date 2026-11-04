package com.dandi.nyummy.e2e.coin

import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// 받기를 실제로 저장하면 prod에 코인이 남으므로 @Smoke를 붙이지 않는다.
// 고정 계정은 한 번만 받고 되돌리는 API가 없어, 첫 실행에서만 코인이 3000 늘고 이후 실행은 거부부터 받는다.
// 받았을 때의 잔액과 내역은 단위 테스트와 CoinLockConcurrencyTest에서 항상 확인한다.
class CoinGiftE2eTest {

    @Test
    fun `선물 코인은 한 번만 받고 다른 그룹과 패키지로 다시 요청해도 거부한다`() {
        val balanceBefore = getBalance()

        val firstResponse = postCoinGift("A", mapOf("coinPackageId" to 3))
        val expectedBalance = if (firstResponse.statusCode == 200) {
            firstResponse.then().body("coinQuantity", equalTo(3000))
            firstResponse.then().body("balance", equalTo(balanceBefore + 3000))
            balanceBefore + 3000
        } else {
            firstResponse.then().validateError(409, "api.coin.coinGiftAlreadyClaimed")
            balanceBefore
        }

        postCoinGift("B", mapOf("coinPackageId" to 1)) Then {
            validateError(409, "api.coin.coinGiftAlreadyClaimed")
        }

        assertEquals(expectedBalance, getBalance(), "거부된 요청이 잔액을 바꿨습니다")
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

package com.dandi.nyummy.e2e.coin

import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// 받기를 실제로 저장하면 prod에 코인이 남으므로 @Smoke를 붙이지 않는다.
// 고정 계정은 한 번만 코인을 받으므로 잔액 값은 보지 않고 구조와 선물 코인 수만 본다.
class CoinGiftE2eTest {

    @Test
    fun `어떤 패키지를 골라도 3000코인을 받는다`() {
        listOf(1, 6).forEach { coinPackageId ->
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("coinPackageId" to coinPackageId))
            } When {
                post("/api/v1/coins/gifts")
            } Then {
                statusCode(200)
                body("coinQuantity", equalTo(3000))
                body("balance", instanceOf<Any>(Number::class.java))
            }
        }
    }

    @Test
    fun `없는 패키지거나 패키지를 보내지 않으면 거부하고 잔액은 그대로다`() {
        val balanceBefore = getBalance()

        listOf(0, 7).forEach { coinPackageId ->
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("coinPackageId" to coinPackageId))
            } When {
                post("/api/v1/coins/gifts")
            } Then {
                statusCode(404)
                body("code", equalTo("api.coin.coinPackageNotFound"))
            }
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(emptyMap<String, Any>())
        } When {
            post("/api/v1/coins/gifts")
        } Then {
            statusCode(400)
            body("code", equalTo("api.common.invalidInputValue"))
        }

        assertEquals(balanceBefore, getBalance(), "거부된 요청이 잔액을 바꿨습니다")
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
}

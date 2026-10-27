package com.dandi.nyummy.e2e.coin

import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.Test

class CoinE2eTest {
    // 조회만 하므로 prod에서도 돌린다. 계정에 내역이 없어도 통과해야 하므로 원소 구조는 보지 않는다.
    @Smoke
    @Test
    fun `코인 내역을 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/coins/histories")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("coinHistories", "nextCursor", "hasNext"))
            body("coinHistories", instanceOf<Any>(List::class.java))
        }
    }
}

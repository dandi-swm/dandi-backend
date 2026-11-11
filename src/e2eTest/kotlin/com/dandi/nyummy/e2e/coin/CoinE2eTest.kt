package com.dandi.nyummy.e2e.coin

import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    // 계정에 내역이 두 건 이상일 때만 다음 묶음을 볼 수 있어, 없으면 실패가 아니라 건너뛴다.
    @Smoke
    @Test
    fun `nextCursor로 다음 묶음을 조회하면 더 오래된 내역을 받는다`() {
        val firstPage = getCoinHistories(mapOf("size" to 1))
        val nextCursor = firstPage["nextCursor"] as Number? ?: abort("코인 내역이 두 건보다 적어 건너뜁니다")

        val secondPage = getCoinHistories(mapOf("size" to 1, "cursor" to nextCursor))

        assertTrue(firstPage.coinHistoryId() > secondPage.coinHistoryId(), "다음 묶음이 더 오래된 내역이 아닙니다")
    }

    // 같은 내역을 언어만 바꿔 받는다. 내역이 없는 계정은 비교할 것이 없다. 상품 이름은 아직 번역하지 않지만
    // 구매 내역도 "구매"·"を購入" 같은 문장 부분이 달라 제목 전체가 달라진다.
    @Smoke
    @Test
    fun `코인 내역 제목은 X-Language 언어로 온다`() {
        val koreanTitles = getCoinHistoryTitles(language = null)
        val japaneseTitles = getCoinHistoryTitles(language = "ja")

        koreanTitles.filterKeys { it in japaneseTitles }.forEach { (coinHistoryId, korean) ->
            assertNotEquals(
                korean,
                japaneseTitles.getValue(coinHistoryId),
                "일본어 요청에 한국어 코인 내역 제목이 왔습니다: coinHistoryId=$coinHistoryId",
            )
        }
    }

    // coinHistoryId → title
    private fun getCoinHistoryTitles(language: String?): Map<Any?, Any?> {
        val coinHistories: List<Map<String, Any?>> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            if (language != null) header("X-Language", language) else this
        } When {
            get("/api/v1/coins/histories")
        } Then {
            statusCode(200)
        } Extract {
            path("coinHistories")
        }
        return coinHistories.associate { it["coinHistoryId"] to it["title"] }
    }

    private fun getCoinHistories(params: Map<String, Any>): Map<String, Any?> = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        queryParams(params)
    } When {
        get("/api/v1/coins/histories")
    } Then {
        statusCode(200)
    } Extract {
        path("")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.coinHistoryId(): Long =
        ((getValue("coinHistories") as List<Map<String, Any?>>).first().getValue("coinHistoryId") as Number).toLong()
}

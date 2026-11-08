package com.dandi.nyummy.e2e.report

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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.assertEquals

// 구매 성공은 고정 계정의 첫 무료 기회·코인·리포트를 되돌릴 수 없어(취소 API 전) 여기서는 거부만 확인한다.
// 거부는 아무것도 남기지 않으므로, 거부 뒤 목록 첫 묶음과 잔액이 그대로인지 함께 본다.
class ReportE2eTest {

    // 조회만 하므로 prod에서도 돌린다.
    @Smoke
    @Test
    fun `리포트 목록을 지금 구매 가격과 최소 식사 수와 함께 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/reports")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("purchasePrice", "minMealCount", "reports", "nextCursor", "hasNext"))
        }
    }

    @Test
    fun `일요일이 아니거나 아직 끝나지 않은 주는 사지 못하고 아무것도 바뀌지 않는다`() {
        val before = getState()
        val thisSunday = LocalDate.now(ZoneId.of("Asia/Seoul")).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

        postReportPurchase(UUID.randomUUID().toString(), thisSunday.minusDays(6), before.purchasePrice) Then {
            validateError(400, "api.report.invalidWeek")
        }
        postReportPurchase(UUID.randomUUID().toString(), thisSunday, before.purchasePrice) Then {
            validateError(400, "api.report.invalidWeek")
        }

        assertEquals(before, getState(), "거부된 구매가 리포트 목록이나 잔액을 바꿨습니다")
    }

    @Test
    fun `분석이 완료된 식사가 부족한 주는 사지 못하고 아무것도 바뀌지 않는다`() {
        val before = getState()

        // 고정 계정은 2020년에 식사 기록이 없다.
        postReportPurchase(UUID.randomUUID().toString(), LocalDate.parse("2020-01-05"), before.purchasePrice) Then {
            validateError(400, "api.report.notEnoughMeals")
        }

        assertEquals(before, getState(), "거부된 구매가 리포트 목록이나 잔액을 바꿨습니다")
    }

    @Test
    fun `Idempotency-Key 없이 사거나 startDate 없이 사면 거부한다`() {
        val before = getState()

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("startDate" to "2020-01-05", "purchasePrice" to before.purchasePrice))
        } When {
            post("/api/v1/reports")
        } Then {
            validateError(400, "api.common.invalidInputValue")
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(mapOf("purchasePrice" to before.purchasePrice))
        } When {
            post("/api/v1/reports")
        } Then {
            validateError(400, "api.common.invalidInputValue")
        }

        assertEquals(before, getState(), "거부된 구매가 리포트 목록이나 잔액을 바꿨습니다")
    }

    @Test
    fun `없는 리포트는 조회할 수 없다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/reports/${Long.MAX_VALUE}")
        } Then {
            validateError(404, "api.report.notFound")
        }
    }

    private fun postReportPurchase(idempotencyKey: String, startDate: LocalDate, purchasePrice: Int) = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        header("Idempotency-Key", idempotencyKey)
        body(mapOf("startDate" to startDate.toString(), "purchasePrice" to purchasePrice))
    } When {
        post("/api/v1/reports")
    }

    // 구매가 바꿀 수 있는 것: 목록 첫 묶음(리포트·상태), 지금 구매 가격(첫 무료), 잔액
    private fun getState(): ReportState {
        val reports: Map<String, Any?> = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/reports")
        } Then {
            statusCode(200)
        } Extract {
            path("")
        }
        val balance: Int = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/shop/cart")
        } Then {
            statusCode(200)
        } Extract {
            path("summary.balance")
        }

        @Suppress("UNCHECKED_CAST")
        val firstReports = reports.getValue("reports") as List<Map<String, Any?>>
        return ReportState(
            purchasePrice = reports.getValue("purchasePrice") as Int,
            reports = firstReports.map { it.getValue("reportId") to it.getValue("status") },
            balance = balance,
        )
    }

    private fun ValidatableResponse.validateError(status: Int, code: String) {
        statusCode(status)
        body("code", equalTo(code))
    }

    private data class ReportState(val purchasePrice: Int, val reports: List<Pair<Any?, Any?>>, val balance: Int)
}

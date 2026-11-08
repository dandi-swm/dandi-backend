package com.dandi.nyummy.e2e.report

import com.dandi.nyummy.e2e.support.AuthFixture
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.assertEquals

// 구매 성공은 바로 취소해 고정 계정의 잔액과 첫 무료 기회를 되돌린다. 취소된 리포트 행은 남는다(지우는 API가 없다).
// 그래서 @Smoke를 붙이지 않는다. 거부는 아무것도 남기지 않으므로, 거부 뒤 목록 첫 묶음과 잔액이 그대로인지 함께 본다.
class ReportE2eTest {

    @Test
    fun `리포트를 사고 바로 취소하면 잔액과 구매 가격이 돌아오고 다시 취소해도 같은 응답이다`() {
        val before = getState()
        val purchase = createPurchasableReport(before.purchasePrice)
        val reportId = (purchase["reportId"] as Number).toLong()
        assertEquals("WAITING", purchase["status"])
        assertEquals(before.balance - before.purchasePrice, purchase["balance"])

        // 다른 사용자는 취소할 수 없고, 거부된 취소는 아무것도 바꾸지 않는다.
        Given {
            spec(RequestSpecs.createAuthorizedSpec(AuthFixture.otherAccessToken))
        } When {
            post("/api/v1/reports/$reportId/cancel")
        } Then {
            validateError(403, "api.auth.forbidden")
        }

        val canceled: Map<String, Any?> = postReportCancel(reportId) Then {
            // Worker가 취소보다 먼저 리포트를 완료하면 409다. 이때는 되돌릴 수 없어 실패로 남긴다.
            statusCode(200)
        } Extract {
            path("")
        }
        assertEquals("CANCELED", canceled["status"])
        assertEquals(before.purchasePrice, canceled["refundedCoinQuantity"])
        assertEquals(before.balance, canceled["balance"])

        val after = getState()
        assertEquals(before.balance, after.balance, "취소 뒤 잔액")
        assertEquals(before.purchasePrice, after.purchasePrice, "취소 뒤 구매 가격(무료로 샀으면 무료 기회가 돌아온다)")
        val (latestReportId, latestStatus) = after.reports.first()
        assertEquals(reportId, (latestReportId as Number).toLong(), "목록 맨 위가 방금 산 리포트가 아닙니다")
        assertEquals("CANCELED", latestStatus)

        val retried: Map<String, Any?> = postReportCancel(reportId) Then { statusCode(200) } Extract { path("") }
        assertEquals(canceled - "balance", retried - "balance")
        assertEquals(before.balance, getState().balance, "다시 취소한 뒤 잔액")
    }

    @Test
    fun `없는 리포트는 취소할 수 없다`() {
        postReportCancel(Long.MAX_VALUE) Then {
            validateError(404, "api.report.notFound")
        }
    }

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

    private fun postReportCancel(reportId: Long) = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        post("/api/v1/reports/$reportId/cancel")
    }

    /**
     * 지난 8주 중 살 수 있는 가장 최근 주의 리포트를 산다. 끝나면 호출한 쪽이 취소한다.
     * 식사가 부족하거나 이미 리포트가 있는 주는 건너뛰고, 살 수 있는 주가 없거나 코인이 부족하면 검증할 수 없으니 건너뛴다.
     */
    private fun createPurchasableReport(purchasePrice: Int): Map<String, Any?> {
        val thisSunday = LocalDate.now(ZoneId.of("Asia/Seoul")).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        (1L..8L).forEach { weeksAgo ->
            val response = postReportPurchase(
                UUID.randomUUID().toString(),
                thisSunday.minusWeeks(weeksAgo),
                purchasePrice,
            )
                .then()
                .extract()
            when (response.statusCode()) {
                200 -> return response.path("")

                400, 409 -> {
                    val code: String = response.path("code")
                    if (code == "api.report.insufficientBalance") abort<Unit>("고정 계정의 코인이 부족해 건너뜁니다")
                    if (code != "api.report.notEnoughMeals" && code != "api.report.duplicateWeek") {
                        throw AssertionError("예상하지 못한 구매 거부: $code")
                    }
                }

                else -> throw AssertionError("예상하지 못한 구매 응답: ${response.statusCode()}")
            }
        }
        abort<Unit>("고정 계정에 지난 8주 중 살 수 있는 주가 없어 건너뜁니다")
        throw IllegalStateException("abort가 테스트를 멈춥니다")
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

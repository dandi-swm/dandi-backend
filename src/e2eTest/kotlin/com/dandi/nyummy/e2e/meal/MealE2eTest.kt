package com.dandi.nyummy.e2e.meal

import com.dandi.nyummy.e2e.support.ImageFixture
import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.awaitility.kotlin.withAlias
import org.awaitility.kotlin.withPollInterval
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.oneOf
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class MealE2eTest {
    @Test
    fun `식사를 만들면 영양 분석이 완료된다`() {
        val imageKey = ImageFixture.createUploadedImage("MEAL", "/images/cake-undated.jpeg", "image/jpeg")
        val mealId = createMeal(imageKey)

        try {
            val status = waitForAnalysis(mealId)
            // FAILED는 대부분 외부(Gemini 장애·쿼터) 원인이다. Jenkins 로그만 보고 구분할 수 있게 상태를 남긴다.
            assertEquals("COMPLETED", status, "영양 분석이 완료되지 않았습니다(mealId=$mealId)")
        } finally {
            // 식사 삭제는 기록 시도 횟수를 되돌리지 않는다(dev는 상한을 올려 두었다). 정리는 결과를 검증하지 않는다.
            Given { spec(RequestSpecs.createAuthorizedSpec()) } When { delete("/api/v1/meals/$mealId") }
        }
    }

    private fun createMeal(imageKey: String): Long = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        body(mapOf("imageKey" to imageKey))
    } When {
        post("/api/v1/meals")
    } Then {
        statusCode(200)
        body("keySet()", hasItems("id", "status"))
        // 분석은 비동기라 생성 직후에는 보통 아직 끝나지 않았다.
        body("status", oneOf("WAITING", "ANALYZING", "COMPLETED"))
    } Extract {
        jsonPath().getLong("id")
    }

    private fun getMealStatus(mealId: Long): String = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v1/meals/$mealId/analysis")
    } Then {
        statusCode(200)
    } Extract {
        path("status")
    }

    // 끝난 상태(COMPLETED·FAILED)가 될 때까지 기다리고 그 상태를 돌려준다.
    private fun waitForAnalysis(mealId: Long): String? = await withAlias "영양 분석 완료 대기(mealId=$mealId)" withPollInterval
        ANALYSIS_POLL_INTERVAL atMost ANALYSIS_TIMEOUT untilCallTo { getMealStatus(mealId) } matches
        { it in FINISHED_STATUSES }

    companion object {
        private val FINISHED_STATUSES = setOf("COMPLETED", "FAILED")

        // Gemini 읽기 제한 시간(app.ai.read-timeout 30s)의 3배. 큐 대기와 재시도까지 감안한다.
        private val ANALYSIS_TIMEOUT: Duration = Duration.ofSeconds(90)
        private val ANALYSIS_POLL_INTERVAL: Duration = Duration.ofSeconds(2)
    }
}

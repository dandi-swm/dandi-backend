package com.dandi.nyummy.e2e.meal

import com.dandi.nyummy.e2e.support.ImageFixture
import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.awaitility.kotlin.await
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.oneOf
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MealE2eTest {
    companion object {
        private val FINISHED_STATUSES = setOf("COMPLETED", "FAILED")

        // Gemini 읽기 제한 시간(app.ai.read-timeout 30s)의 3배. 큐 대기와 재시도까지 감안한다.
        private val ANALYSIS_TIMEOUT: Duration = Duration.ofSeconds(90)
        private val ANALYSIS_POLL_INTERVAL: Duration = Duration.ofSeconds(2)

        // 서버는 식사 날짜를 한국 시간 기준으로 나눈다(MealAttemptService.MEAL_ZONE).
        private val MEAL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }

    @Test
    fun `식사를 기록하고 분석·조회·수정·평가·삭제한다`() {
        val today = LocalDate.now(MEAL_ZONE)
        val summaryBefore = getTodayMealSummary()

        val imageKey = ImageFixture.createUploadedImage("MEAL", "/images/cake-undated.jpeg", "image/jpeg")
        val mealId = createMeal(imageKey)

        try {
            // 1. 분석 완료
            val status = waitForAnalysis(mealId)
            // FAILED는 대부분 외부(Gemini 장애·쿼터) 원인이다. Jenkins 로그만 보고 구분할 수 있게 상태를 남긴다.
            assertEquals("COMPLETED", status, "영양 분석이 완료되지 않았습니다(mealId=$mealId)")

            // 2. 상세: 영양 정보·이미지·평가 가능 여부
            val meal = Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/meals/$mealId")
            } Then {
                statusCode(200)
                body(
                    "keySet()",
                    hasItems(
                        "mealId", "name", "mealAt", "status", "nutrition",
                        "imageUrl", "canCreateAnalysisFeedback", "catComment", "iconId",
                    ),
                )
                body("nutrition.keySet()", hasItems("calory", "carbs", "protein", "fat"))
                body("status", equalTo("COMPLETED"))
                // dev는 app.meal.analysis-feedback-rate가 100이라 분석이 끝난 모든 식사를 평가할 수 있다.
                body("canCreateAnalysisFeedback", equalTo(true))
            } Extract {
                jsonPath()
            }
            // 케이크 사진이므로 칼로리가 0이면 분석 결과가 비어 있는 것이다.
            assertTrue(meal.getInt("nutrition.calory") > 0, "분석한 칼로리가 0입니다(mealId=$mealId)")

            // 앱은 imageUrl로 바로 이미지를 받는다. 업로드 확정(temp → 최종)이 실패했으면 여기서 깨진다.
            Given {
                urlEncodingEnabled(false)
            } When {
                get(meal.getString("imageUrl"))
            } Then {
                statusCode(200)
                contentType(startsWith("image/jpeg"))
            }

            // 3. 오늘의 일일 목록·월간 캘린더·홈에 반영
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                queryParams(mapOf("year" to today.year, "month" to today.monthValue, "day" to today.dayOfMonth))
            } When {
                get("/api/v1/meals/daily")
            } Then {
                statusCode(200)
                body("keySet()", hasItems("date", "meals", "dailyNutrition"))
                body("dailyNutrition.keySet()", hasItems("current", "target"))
                body(
                    "meals.find { it.mealId == $mealId }.keySet()",
                    hasItems("mealId", "name", "mealAt", "calory", "carbs", "protein", "fat", "status", "iconId"),
                )
            }

            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                queryParams(mapOf("year" to today.year, "month" to today.monthValue))
            } When {
                get("/api/v1/meals/monthly")
            } Then {
                statusCode(200)
                body("keySet()", hasItems("year", "month", "days"))
                body(
                    "days.find { it.date == '$today' }.keySet()",
                    hasItems("date", "isCurrentMonth", "dailyNutritionEvaluation", "foodIconIds"),
                )
                body(
                    "days.find { it.date == '$today' }.dailyNutritionEvaluation",
                    oneOf("POSITIVE", "NEGATIVE", "UNRECORDED"),
                )
            }

            val summaryAfterCreate = getTodayMealSummary()
            assertEquals(summaryBefore.recordedCount + 1, summaryAfterCreate.recordedCount, "홈의 오늘 식사 수")
            assertEquals(summaryBefore.attemptCount + 1, summaryAfterCreate.attemptCount, "홈의 오늘 기록 시도 횟수")

            // 4. 이름 수정: 수정된 식사가 응답으로 온다
            val updatedName = "E2E 케이크 ${UUID.randomUUID().toString().take(8)}"
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                queryParam("name", updatedName)
            } When {
                put("/api/v1/meals/$mealId")
            } Then {
                statusCode(200)
                body("name", equalTo(updatedName))
            }

            // 5. 분석 평가: 식사당 한 번만, 평가 후에는 평가 가능 여부가 꺼진다
            val feedback = mapOf("rating" to 4.5, "reason" to "E2E 테스트가 남긴 평가입니다.")
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(feedback)
            } When {
                post("/api/v1/meals/$mealId/analysis/feedback")
            } Then {
                statusCode(201)
            }
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(feedback)
            } When {
                post("/api/v1/meals/$mealId/analysis/feedback")
            } Then {
                statusCode(409)
                body("code", equalTo("api.meal.analysisFeedbackAlreadyExists"))
            }
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/meals/$mealId")
            } Then {
                statusCode(200)
                body("canCreateAnalysisFeedback", equalTo(false))
            }

            // 6. 삭제: 상세는 404, 홈의 식사 수는 돌아오지만 기록 시도 횟수는 돌아오지 않는다
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                delete("/api/v1/meals/$mealId")
            } Then {
                statusCode(204)
            }
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/meals/$mealId")
            } Then {
                statusCode(404)
                body("code", equalTo("api.meal.notFound"))
            }

            val summaryAfterDelete = getTodayMealSummary()
            assertEquals(summaryBefore.recordedCount, summaryAfterDelete.recordedCount, "삭제 후 홈의 오늘 식사 수")
            assertEquals(summaryAfterCreate.attemptCount, summaryAfterDelete.attemptCount, "삭제 후 기록 시도 횟수")
        } finally {
            // 중간에 실패하면 이미 삭제된 상태일 수 있다(그때는 404). 정리는 결과를 검증하지 않는다.
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
    private fun waitForAnalysis(mealId: Long): String = await
        .alias("영양 분석 완료 대기(mealId=$mealId)")
        .pollInterval(ANALYSIS_POLL_INTERVAL)
        .atMost(ANALYSIS_TIMEOUT)
        .until({ getMealStatus(mealId) }, { it in FINISHED_STATUSES })

    private fun getTodayMealSummary(): TodayMealSummary {
        val home = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/home")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath()
        }
        return TodayMealSummary(
            recordedCount = home.getInt("todayMealSummary.todayRecordedCount"),
            attemptCount = home.getInt("todayMealSummary.todayAttemptCount"),
        )
    }

    private data class TodayMealSummary(val recordedCount: Int, val attemptCount: Int)
}

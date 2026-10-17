package com.dandi.nyummy.e2e.meal

import com.dandi.nyummy.e2e.support.ImageFixture
import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.oneOf
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MealE2eTest {
    companion object {
        // 첫 분석을 포함해 최대 3번 시도한다. 그래도 실패하면 일시적 흔들림이 아니라 실제 문제로 본다.
        private const val MAX_ANALYSIS_RETRY_COUNT = 2

        // 서버는 식사 날짜를 한국 시간 기준으로 나눈다(MealAttemptService.MEAL_ZONE).
        private val MEAL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }

    @Test
    fun `식사를 기록하고 분석·조회·수정·평가·삭제한다`() {
        val today = LocalDate.now(MEAL_ZONE)
        val summaryBefore = getTodayMealSummary()

        val imageKey = ImageFixture.createUploadedImage("MEAL", "/images/cake-undated.jpeg", "image/jpeg")
        val created = createMeal(imageKey)
        val mealId = created.id

        try {
            // 1. 분석 완료. Gemini 응답은 실행마다 달라 가끔 실패한다. 앱처럼 재시도 API로 다시 시도한다.
            var status = created.status
            var retryCount = 0
            while (status == "FAILED" && retryCount < MAX_ANALYSIS_RETRY_COUNT) {
                status = retryNutritionAnalysis(mealId)
                retryCount++
            }
            assertEquals(
                "COMPLETED",
                status,
                "영양 분석이 재시도 ${retryCount}번 후에도 완료되지 않았습니다(mealId=$mealId). " +
                    "dev 로그의 '영양 분석 실패' 메시지를 확인하세요.",
            )

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

    // 조회만 하므로 prod에서도 돌린다. 계정에 식사가 없어도 통과해야 한다.
    @Smoke
    @Test
    fun `오늘의 일일 식사 목록을 조회한다`() {
        val today = LocalDate.now(MEAL_ZONE)

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("year" to today.year, "month" to today.monthValue, "day" to today.dayOfMonth))
        } When {
            get("/api/v1/meals/daily")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("date", "meals", "dailyNutrition"))
            body("date", equalTo(today.toString()))
            body("dailyNutrition.current.keySet()", hasItems("calory", "carbs", "protein", "fat"))
            body("dailyNutrition.target.keySet()", hasItems("calory", "carbs", "protein", "fat"))
        }
    }

    // 조회만 하므로 prod에서도 돌린다. 캘린더는 식사가 없어도 주 단위 그리드를 채워 내려준다.
    @Smoke
    @Test
    fun `이번 달 식사 캘린더를 조회한다`() {
        val today = LocalDate.now(MEAL_ZONE)

        val days = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("year" to today.year, "month" to today.monthValue))
        } When {
            get("/api/v1/meals/monthly")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("year", "month", "days"))
            body("days[0].keySet()", hasItems("date", "isCurrentMonth", "dailyNutritionEvaluation", "foodIconIds"))
            body("days.find { it.date == '$today' }.isCurrentMonth", equalTo(true))
        } Extract {
            jsonPath().getList<Any>("days")
        }

        // 앞뒤 달 날짜를 포함해 주 단위(일~토)로 채운 그리드라 항상 7의 배수다.
        assertEquals(0, days.size % 7, "캘린더 칸 수가 주 단위가 아닙니다: ${days.size}")
    }

    // 분석은 생성 요청 안에서 동기로 끝나므로 응답의 상태가 최종 결과다.
    private fun createMeal(imageKey: String): MealStatus = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        body(mapOf("imageKey" to imageKey))
    } When {
        post("/api/v1/meals")
    } Then {
        statusCode(200)
        body("keySet()", hasItems("id", "status"))
        body("status", oneOf("COMPLETED", "FAILED"))
    } Extract {
        val response = jsonPath()
        MealStatus(id = response.getLong("id"), status = response.getString("status"))
    }

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

    private fun retryNutritionAnalysis(mealId: Long): String = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        post("/api/v1/meals/$mealId/analysis")
    } Then {
        statusCode(200)
        body("status", oneOf("COMPLETED", "FAILED"))
    } Extract {
        path("status")
    }

    private data class TodayMealSummary(val recordedCount: Int, val attemptCount: Int)
    private data class MealStatus(val id: Long, val status: String)
}

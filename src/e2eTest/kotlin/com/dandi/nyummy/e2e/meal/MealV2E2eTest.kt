package com.dandi.nyummy.e2e.meal

import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * v2 식사 API. 지금은 고정 응답(Stub)이라 값이 아니라 응답 모양과 요청 검증을 본다.
 *
 * Stub은 dev에만 배포하므로 prod에서 도는 @Smoke는 붙이지 않는다.
 * 월간 캘린더·삭제·튜토리얼·재시도·평가는 v1 서비스를 그대로 부르므로 데이터를 남기지 않는 경로만 본다.
 */
class MealV2E2eTest {

    @Test
    fun `식사를 기록하면 대표 이름과 계산 전 음식 줄을 준다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("imageKey" to "meals/stub.jpg"))
        } When {
            post("/api/v2/meals")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("id", "status", "rewardBoxCategory", "name", "foods"))
            body("status", equalTo("WAITING"))
            body("foods", hasSize<Any>(2))
            body("foods[0].keySet()", hasItems("mealFoodId", "name", "portion", "nutrition", "isNutritionFixed"))
            body("foods[0].nutrition", nullValue())
        }
    }

    @Test
    fun `이미지 키가 없으면 식사를 기록하지 않는다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("imageKey" to ""))
        } When {
            post("/api/v2/meals")
        } Then {
            statusCode(400)
            body("code", equalTo("api.common.invalidInputValue"))
        }
    }

    @Test
    fun `분석 상태와 식사 상세를 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v2/meals/1/analysis")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("id", "status"))
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v2/meals/1")
        } Then {
            statusCode(200)
            body(
                "keySet()",
                hasItems(
                    "mealId", "analysisVersion", "name", "mealAt", "status", "nutrition",
                    "imageUrl", "canCreateAnalysisFeedback", "catComment", "iconId", "foods",
                ),
            )
            body("foods[0].nutrition.keySet()", hasItems("calory", "carbs", "protein", "fat"))
        }
    }

    @Test
    fun `하루 식사 목록은 식사마다 분석 버전을 준다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("year" to today.year, "month" to today.monthValue, "day" to today.dayOfMonth))
        } When {
            get("/api/v2/meals/daily")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("date", "meals", "dailyNutrition"))
            body("meals.analysisVersion", hasItems("V1", "V2"))
        }
    }

    @Test
    fun `음식 목록을 보내면 다시 계산을 기다린다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(
                mapOf(
                    "foods" to listOf(
                        mapOf("mealFoodId" to 11, "name" to "제육볶음", "portion" to 1.5, "nutrition" to null),
                        mapOf("mealFoodId" to null, "name" to "김치", "portion" to 0.5, "nutrition" to null),
                        mapOf(
                            "mealFoodId" to 12,
                            "name" to "현미밥",
                            "portion" to 1.0,
                            "nutrition" to mapOf("calory" to 300, "carbs" to 65, "protein" to 6, "fat" to 2),
                        ),
                    ),
                ),
            )
        } When {
            put("/api/v2/meals/1/foods")
        } Then {
            statusCode(200)
            body("status", equalTo("WAITING"))
        }
    }

    @Test
    fun `인분 범위나 단위가 틀리거나 목록이 비면 받지 않는다`() {
        val invalidBodies = listOf(
            mapOf("foods" to listOf(mapOf("name" to "밥", "portion" to 0.7))),
            mapOf("foods" to listOf(mapOf("name" to "밥", "portion" to 5.5))),
            mapOf("foods" to listOf(mapOf("name" to "밥", "portion" to 0))),
            mapOf("foods" to emptyList<Any>()),
        )

        invalidBodies.forEach { invalidBody ->
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(invalidBody)
            } When {
                put("/api/v2/meals/1/foods")
            } Then {
                statusCode(400)
                body("code", equalTo("api.common.invalidInputValue"))
            }
        }
    }

    @Test
    fun `대표 이름을 바꾸면 바꾼 이름으로 상세를 준다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("name" to "점심 정식"))
        } When {
            put("/api/v2/meals/1")
        } Then {
            statusCode(200)
            body("name", equalTo("점심 정식"))
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("name" to "가".repeat(21)))
        } When {
            put("/api/v2/meals/1")
        } Then {
            statusCode(400)
            body("code", equalTo("api.common.invalidInputValue"))
        }
    }

    @Test
    fun `기록을 취소한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            post("/api/v2/meals/1/cancel")
        } Then {
            statusCode(204)
        }
    }

    @Test
    fun `월간 캘린더를 v2 경로로 조회한다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            queryParams(mapOf("year" to today.year, "month" to today.monthValue))
        } When {
            get("/api/v2/meals/monthly")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("year", "month", "days"))
        }
    }

    // 없는 식사로 부르면 v1과 같이 404다. 식사가 없으므로 지우거나 분석을 다시 시작하는 일이 생기지 않는다.
    @Test
    fun `없는 식사는 v2 경로로도 지우거나 재분석하거나 평가할 수 없다`() {
        val notExistingMealId = Long.MAX_VALUE

        Given { spec(RequestSpecs.createAuthorizedSpec()) } When {
            delete("/api/v2/meals/$notExistingMealId")
        } Then {
            statusCode(404)
            body("code", equalTo("api.meal.notFound"))
        }

        Given { spec(RequestSpecs.createAuthorizedSpec()) } When {
            post("/api/v2/meals/$notExistingMealId/analysis")
        } Then {
            statusCode(404)
            body("code", equalTo("api.meal.notFound"))
        }

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("rating" to 4.5))
        } When {
            post("/api/v2/meals/$notExistingMealId/analysis/feedback")
        } Then {
            statusCode(404)
            body("code", equalTo("api.meal.notFound"))
        }
    }

    // 고정 계정은 튜토리얼을 이미 마쳤으므로 항상 거부된다(MealE2eTest와 같은 이유).
    @Test
    fun `이미 튜토리얼을 마친 사용자는 v2 경로로도 튜토리얼 식사를 만들 수 없다`() {
        val latestMailIdBefore = getLatestMailId()

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            post("/api/v2/meals/tutorial")
        } Then {
            statusCode(409)
            body("code", equalTo("api.meal.tutorialAlreadyCompleted"))
        }

        // 거부됐어도 보상 상자 우편이 먼저 만들어졌을 수 있으므로 가장 최근 우편이 그대로인지 본다.
        assertEquals(latestMailIdBefore, getLatestMailId(), "튜토리얼 거부 후 새 우편이 생겼습니다")
    }

    private fun getLatestMailId(): Long? = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        queryParam("size", 1)
    } When {
        get("/api/v1/mails")
    } Then {
        statusCode(200)
    } Extract {
        jsonPath().get<Number?>("mails[0].mailId")?.toLong()
    }
}

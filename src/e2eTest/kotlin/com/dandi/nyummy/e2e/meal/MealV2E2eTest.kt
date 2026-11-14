package com.dandi.nyummy.e2e.meal

import com.dandi.nyummy.e2e.support.AuthFixture
import com.dandi.nyummy.e2e.support.ImageFixture
import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.path.json.JsonPath
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.oneOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v2 식사 API. 식사를 기록하면 서버가 음식 줄을 인식하고, 음식 목록을 보내면 Worker가 영양을 계산한다.
 *
 * 하루 기록 횟수에 상한이 있어 식사는 한 번만 만들고, 그 식사 하나로 기록부터 취소까지 이어서 본다.
 * 식사를 만들므로 prod에서 도는 @Smoke는 붙이지 않는다.
 */
class MealV2E2eTest {

    @Test
    fun `식사를 기록해 음식 목록을 보내고 영양 계산이 끝난 뒤 고치고 취소한다`() {
        val today = LocalDate.now(MEAL_ZONE)
        val imageKey = ImageFixture.createUploadedImage("MEAL", "/images/cake-undated.jpeg", "image/jpeg")
        val latestMailIdBefore = getLatestMailId()

        val created = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("imageKey" to imageKey))
        } When {
            post("/api/v2/meals")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("id", "status", "rewardBoxCategory", "name", "foods"))
            // 서버는 응답 기한 안에 인식하면 CONFIRMING, 못 끝내면 RECOGNIZING으로 응답한다. 어느 쪽이 될지는 그때의 Gemini 응답 시간에 달렸다.
            // FAILED면 테스트 사진을 음식이 아니라고 본 것이다.
            body("status", oneOf("CONFIRMING", "RECOGNIZING"))
            body("rewardBoxCategory", oneOf("BRONZE", "SILVER", "GOLD"))
        } Extract {
            jsonPath()
        }
        val mealId = created.getLong("id")
        var rewardBoxMailId: Long? = null

        try {
            // 1. 기록 응답의 대표 이름과 음식 줄
            if (created.getString("status") == "CONFIRMING") {
                assertRecognizedFoods(created.getString("name"), created.getList("foods"))
            } else {
                // 인식 전이라 이름과 음식 줄이 없다.
                assertNull(created.getString("name"), "인식 중인 식사에 이름이 있습니다")
                assertNull(created.getList<Any>("foods"), "인식 중인 식사에 음식 줄이 있습니다")
            }

            // 2. 기록 보상 상자 우편이 하나 생긴다.
            rewardBoxMailId = Given {
                spec(RequestSpecs.createAuthorizedSpec())
                queryParam("size", 1)
            } When {
                get("/api/v1/mails")
            } Then {
                statusCode(200)
                body("mails[0].category", equalTo("REWARD_BOX"))
                body("mails[0].isClaimed", equalTo(false))
            } Extract {
                jsonPath().getLong("mails[0].mailId")
            }
            assertTrue(rewardBoxMailId != latestMailIdBefore, "식사를 기록했는데 새 보상 상자 우편이 없습니다")

            // 3. 같은 사진으로는 다시 기록할 수 없다.
            val attemptCountBefore = getTodayAttemptCount()
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("imageKey" to imageKey))
            } When {
                post("/api/v2/meals")
            } Then {
                statusCode(409)
                body("code", equalTo("api.meal.duplicateImageKey"))
            }
            // 거부됐어도 식사나 우편이 먼저 만들어졌을 수 있으므로 기록 시도 횟수와 가장 최근 우편이 그대로인지 본다.
            assertEquals(attemptCountBefore, getTodayAttemptCount(), "거부된 기록이 시도 횟수에 들어갔습니다")
            assertEquals(rewardBoxMailId, getLatestMailId(), "거부된 기록에 보상 상자 우편이 생겼습니다")

            // 4. 인식이 끝나 음식 목록이 준비된다. 기한 안에 인식하지 못했으면 Worker가 인식할 때까지 기다린다.
            // 영양 계산은 음식 목록을 보내거나 5분이 지나야 시작하므로 여기서는 사용자의 확인을 기다리는 CONFIRMING에 머문다.
            assertEquals(
                "CONFIRMING",
                waitForStatus(mealId, setOf("CONFIRMING", "FAILED")),
                "음식 인식이 끝나지 않았거나 실패했습니다(mealId=$mealId). dev Worker 로그를 확인하세요.",
            )
            val confirmingMeal = getMeal(mealId)
            assertEquals("V2", confirmingMeal.getString("analysisVersion"))
            assertEquals(0, confirmingMeal.getInt("nutrition.calory"), "계산 전 식사에 칼로리가 있습니다")
            assertNull(confirmingMeal.get<Any?>("iconId"), "계산 전 식사에 아이콘이 있습니다")
            assertNull(confirmingMeal.getString("catComment"), "계산 전 식사에 코멘트가 있습니다")
            val recognizedFoods = confirmingMeal.getList<Map<String, Any?>>("foods")
            assertRecognizedFoods(confirmingMeal.getString("name"), recognizedFoods)

            // 5. 남의 식사는 볼 수도, 음식 목록을 보낼 수도 없다.
            val otherSpec = RequestSpecs.createAuthorizedSpec(AuthFixture.otherAccessToken)
            Given { spec(otherSpec) } When { get("/api/v2/meals/$mealId") } Then {
                statusCode(403)
                body("code", equalTo("api.auth.forbidden"))
            }
            Given {
                spec(otherSpec)
                body(mapOf("foods" to listOf(mapOf("mealFoodId" to null, "name" to "남이 넣은 줄", "portion" to 1.0))))
            } When {
                put("/api/v2/meals/$mealId/foods")
            } Then {
                statusCode(403)
                body("code", equalTo("api.auth.forbidden"))
            }

            // 6. 받지 않는 음식 목록: 이 식사의 줄이 아닌 id, 없는 아이콘, 0.5 단위가 아닌 인분
            val firstFood = recognizedFoods.first()
            val invalidFoods = listOf(
                Triple(
                    404,
                    "api.meal.mealFoodNotFound",
                    mapOf("mealFoodId" to Long.MAX_VALUE, "name" to "남의 줄", "portion" to 1.0),
                ),
                Triple(
                    400,
                    "api.common.invalidInputValue",
                    convertFoodBody(firstFood) + mapOf("iconId" to NOT_EXISTING_ICON_ID),
                ),
                Triple(400, "api.common.invalidInputValue", convertFoodBody(firstFood) + mapOf("portion" to 0.7)),
            )
            invalidFoods.forEach { (expectedStatus, expectedCode, invalidFood) ->
                Given {
                    spec(RequestSpecs.createAuthorizedSpec())
                    body(mapOf("foods" to listOf(invalidFood)))
                } When {
                    put("/api/v2/meals/$mealId/foods")
                } Then {
                    statusCode(expectedStatus)
                    body("code", equalTo(expectedCode))
                }
            }

            // 7. 실패하지 않은 식사는 재분석할 수 없다.
            Given { spec(RequestSpecs.createAuthorizedSpec()) } When {
                post("/api/v2/meals/$mealId/analysis")
            } Then {
                statusCode(409)
                body("code", equalTo("api.meal.analysisNotRetryable"))
            }

            // 거부된 요청들이 음식 줄과 상태를 바꾸지 않았는지 본다. 4xx가 와도 저장이 먼저 일어났을 수 있다.
            val untouchedMeal = getMeal(mealId)
            assertEquals("CONFIRMING", untouchedMeal.getString("status"))
            assertEquals(
                recognizedFoods.map { it["mealFoodId"] to it["name"] },
                untouchedMeal.getList<Map<String, Any?>>("foods").map { it["mealFoodId"] to it["name"] },
                "거부된 요청이 음식 줄을 바꿨습니다",
            )

            // 8. 음식 목록을 보낸다. 맨 위 줄의 인분을 바꾸고 김치 반 인분을 더한다.
            // 5분을 기다리던 계산 대신 바로 계산한다. 처음 계산이라 Worker가 1인분 영양과 아이콘을 구하고 코멘트를 만든다. 응답은 WAITING이다.
            val firstPortion = if (convertPortion(firstFood) == 1.0) 1.5 else 1.0
            val sentFoods = listOf(convertFoodBody(firstFood) + mapOf("portion" to firstPortion)) +
                recognizedFoods.drop(1).map { convertFoodBody(it) } +
                mapOf("mealFoodId" to null, "name" to ADDED_FOOD_NAME, "portion" to 0.5)
            assertEquals("WAITING", updateMealFoods(mealId, sentFoods))

            // 9. 영양 계산이 끝난다.
            assertEquals(
                "COMPLETED",
                waitForStatus(mealId, setOf("COMPLETED", "FAILED")),
                "영양 계산이 끝나지 않았거나 실패했습니다(mealId=$mealId). dev Worker 로그를 확인하세요.",
            )
            val completedMeal = getMeal(mealId)
            val completedFoods = completedMeal.getList<Map<String, Any?>>("foods")
            assertEquals(sentFoods.size, completedFoods.size)
            assertEquals(firstFood["mealFoodId"], completedFoods.first()["mealFoodId"])
            assertEquals(firstPortion, convertPortion(completedFoods.first()))
            assertEquals(ADDED_FOOD_NAME, completedFoods.last()["name"])
            completedFoods.forEach { food ->
                assertNotNull(food["nutrition"], "계산이 끝났는데 줄 영양이 없습니다: ${food["name"]}")
                assertNotNull(food["iconId"], "계산이 끝났는데 줄 아이콘이 없습니다: ${food["name"]}")
            }
            // 식사 합계는 줄 영양의 합이다. 합계는 소수로 더한 뒤 한 번 반올림하므로 줄마다 1까지 차이 날 수 있다.
            val completedCalory = completedMeal.getInt("nutrition.calory")
            assertTrue(completedCalory > 0, "계산이 끝났는데 칼로리가 0입니다")
            assertTrue(
                abs(completedCalory - completedFoods.sumOf { convertCalory(it) }) <= completedFoods.size,
                "식사 합계($completedCalory)가 줄 영양의 합과 다릅니다",
            )
            // 맨 위 줄(대표 음식)의 아이콘이 식사 아이콘이다.
            assertEquals(completedFoods.first()["iconId"], completedMeal.get<Any?>("iconId"))
            val catComment = completedMeal.getString("catComment")
            assertTrue(
                !catComment.isNullOrBlank() && catComment.length <= 255,
                "계산이 끝났는데 고양이 코멘트가 없거나 너무 깁니다: $catComment",
            )

            // 10. 하루 목록에 V2 식사로 나오고 합계가 같다.
            val dailyMeal = getDailyMeals(today).single { (it["mealId"] as Number).toLong() == mealId }
            assertEquals("V2", dailyMeal["analysisVersion"])
            assertEquals("COMPLETED", dailyMeal["status"])
            assertEquals(completedCalory, dailyMeal["calory"])

            // 11. 인분만 바꿔 다시 보낸다. 새로 구할 값이 없으므로 요청 안에서 계산이 끝나 응답이 COMPLETED다.
            val addedFood = completedFoods.last()
            val portionChangedFoods = completedFoods.dropLast(1).map { convertFoodBody(it) } +
                (convertFoodBody(addedFood) + mapOf("portion" to 1.0))
            assertEquals("COMPLETED", updateMealFoods(mealId, portionChangedFoods))
            val portionChangedMeal = getMeal(mealId)
            assertEquals("COMPLETED", portionChangedMeal.getString("status"))
            assertEquals(1.0, convertPortion(portionChangedMeal.getList<Map<String, Any?>>("foods").last()))
            // 코멘트는 처음 계산 때 만든 것이 그대로 남는다.
            assertEquals(catComment, portionChangedMeal.getString("catComment"))

            // 12. 줄 영양을 직접 적어 보낸다. 그 줄은 적은 값으로 고정되고, 이번에도 요청 안에서 끝난다.
            val fixedNutrition = mapOf("calory" to 15, "carbs" to 3, "protein" to 1, "fat" to 0)
            val nutritionFixedFoods = completedFoods.dropLast(1).map { convertFoodBody(it) } +
                (convertFoodBody(addedFood) + mapOf("portion" to 1.0, "nutrition" to fixedNutrition))
            assertEquals("COMPLETED", updateMealFoods(mealId, nutritionFixedFoods))
            val fixedFood = getMeal(mealId).getList<Map<String, Any?>>("foods").last()
            assertEquals(true, fixedFood["isNutritionFixed"])
            assertEquals(fixedNutrition, fixedFood["nutrition"])

            // 13. 대표 이름을 바꾼다. 음식 줄은 그대로다.
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("name" to UPDATED_MEAL_NAME))
            } When {
                put("/api/v2/meals/$mealId")
            } Then {
                statusCode(200)
                body("name", equalTo(UPDATED_MEAL_NAME))
                body("foods.size()", equalTo(sentFoods.size))
            }
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("name" to "가".repeat(21)))
            } When {
                put("/api/v2/meals/$mealId")
            } Then {
                statusCode(400)
                body("code", equalTo("api.common.invalidInputValue"))
            }
            assertEquals(UPDATED_MEAL_NAME, getMeal(mealId).getString("name"), "거부된 이름 수정이 이름을 바꿨습니다")

            // 14. 기록을 취소하면 더 조회되지 않고 하루 목록에서도 빠진다.
            Given { spec(RequestSpecs.createAuthorizedSpec()) } When {
                post("/api/v2/meals/$mealId/cancel")
            } Then {
                statusCode(204)
            }
            Given { spec(RequestSpecs.createAuthorizedSpec()) } When { get("/api/v2/meals/$mealId") } Then {
                statusCode(404)
                body("code", equalTo("api.meal.notFound"))
            }
            assertFalse(
                getDailyMeals(today).any { (it["mealId"] as Number).toLong() == mealId },
                "취소한 식사가 하루 목록에 남아 있습니다",
            )
        } finally {
            // 중간에 실패하면 식사가 남아 있다. 이미 취소했으면 404다. 정리는 결과를 검증하지 않는다.
            Given { spec(RequestSpecs.createAuthorizedSpec()) } When { delete("/api/v2/meals/$mealId") }
            // 기록 보상 상자는 받은 우편만 삭제할 수 있어 받은 뒤 지운다(MealE2eTest와 같다). 결과는 검증하지 않는다.
            rewardBoxMailId?.let { mailId ->
                Given { spec(RequestSpecs.createAuthorizedSpec()) } When { post("/api/v1/mails/$mailId/claim") }
                Given { spec(RequestSpecs.createAuthorizedSpec()) } When { delete("/api/v1/mails/$mailId") }
            }
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

    // 요청 본문 검증은 식사를 읽기 전에 끝나므로 없는 식사 id로 본다. 바뀔 식사가 없어 부작용이 생길 수 없다.
    @Test
    fun `음식 목록의 인분 범위가 틀리거나 목록이 비면 받지 않는다`() {
        val invalidBodies = listOf(
            mapOf("foods" to listOf(mapOf("name" to "밥", "portion" to 5.5))),
            mapOf("foods" to listOf(mapOf("name" to "밥", "portion" to 0))),
            mapOf("foods" to emptyList<Any>()),
        )

        invalidBodies.forEach { invalidBody ->
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(invalidBody)
            } When {
                put("/api/v2/meals/$NOT_EXISTING_MEAL_ID/foods")
            } Then {
                statusCode(400)
                body("code", equalTo("api.common.invalidInputValue"))
            }
        }
    }

    @Test
    fun `음식 줄이 열 개를 넘거나 이름이 길거나 영양 범위를 벗어나면 받지 않는다`() {
        val invalidBodies = listOf(
            mapOf("foods" to List(11) { mapOf("name" to "밥", "portion" to 1.0) }),
            mapOf("foods" to listOf(mapOf("name" to "가".repeat(31), "portion" to 1.0))),
            mapOf("foods" to listOf(mapOf("name" to "", "portion" to 1.0))),
            mapOf(
                "foods" to listOf(
                    mapOf(
                        "name" to "밥",
                        "portion" to 1.0,
                        "nutrition" to mapOf("calory" to 10000, "carbs" to 68, "protein" to 7, "fat" to 1),
                    ),
                ),
            ),
            mapOf(
                "foods" to listOf(
                    mapOf(
                        "name" to "밥",
                        "portion" to 1.0,
                        "nutrition" to mapOf("calory" to 310, "carbs" to 1000, "protein" to 7, "fat" to 1),
                    ),
                ),
            ),
            mapOf(
                "foods" to listOf(
                    mapOf(
                        "name" to "밥",
                        "portion" to 1.0,
                        "nutrition" to mapOf("calory" to 310, "carbs" to 68, "protein" to -1, "fat" to 1),
                    ),
                ),
            ),
        )

        invalidBodies.forEach { invalidBody ->
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(invalidBody)
            } When {
                put("/api/v2/meals/$NOT_EXISTING_MEAL_ID/foods")
            } Then {
                statusCode(400)
                body("code", equalTo("api.common.invalidInputValue"))
            }
        }
    }

    @Test
    fun `월간 캘린더를 v2 경로로 조회한다`() {
        val today = LocalDate.now(MEAL_ZONE)

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

    // 없는 식사는 v2의 어느 API로도 404다. 식사가 없으므로 고치거나 지우거나 분석을 다시 시작하는 일이 생기지 않는다.
    @Test
    fun `없는 식사는 v2 경로로 조회하거나 고치거나 지우거나 재분석하거나 평가할 수 없다`() {
        val validFoods = mapOf("foods" to listOf(mapOf("mealFoodId" to null, "name" to "밥", "portion" to 1.0)))
        val requests = listOf<() -> io.restassured.response.Response>(
            { Given { spec(RequestSpecs.createAuthorizedSpec()) } When { get("/api/v2/meals/$NOT_EXISTING_MEAL_ID") } },
            {
                Given { spec(RequestSpecs.createAuthorizedSpec()) } When {
                    get("/api/v2/meals/$NOT_EXISTING_MEAL_ID/analysis")
                }
            },
            {
                Given {
                    spec(RequestSpecs.createAuthorizedSpec())
                    body(mapOf("name" to "점심"))
                } When { put("/api/v2/meals/$NOT_EXISTING_MEAL_ID") }
            },
            {
                Given {
                    spec(RequestSpecs.createAuthorizedSpec())
                    body(validFoods)
                } When { put("/api/v2/meals/$NOT_EXISTING_MEAL_ID/foods") }
            },
            {
                Given { spec(RequestSpecs.createAuthorizedSpec()) } When {
                    post("/api/v2/meals/$NOT_EXISTING_MEAL_ID/cancel")
                }
            },
            {
                Given {
                    spec(RequestSpecs.createAuthorizedSpec())
                } When { delete("/api/v2/meals/$NOT_EXISTING_MEAL_ID") }
            },
            {
                Given { spec(RequestSpecs.createAuthorizedSpec()) } When {
                    post("/api/v2/meals/$NOT_EXISTING_MEAL_ID/analysis")
                }
            },
            {
                Given {
                    spec(RequestSpecs.createAuthorizedSpec())
                    body(mapOf("rating" to 4.5))
                } When { post("/api/v2/meals/$NOT_EXISTING_MEAL_ID/analysis/feedback") }
            },
        )

        requests.forEach { request ->
            request() Then {
                statusCode(404)
                body("code", equalTo("api.meal.notFound"))
            }
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

    // 인식된 대표 이름과 음식 줄. 영양과 줄 아이콘은 영양 계산 때 정해지므로 인식 직후에는 비어 있다.
    private fun assertRecognizedFoods(name: String?, foods: List<Map<String, Any?>>?) {
        assertTrue(!name.isNullOrBlank() && name.length <= 20, "대표 이름은 1~20자여야 합니다: $name")
        assertTrue(foods != null && foods.size in 1..10, "음식 줄은 1~10개여야 합니다: ${foods?.size}")
        foods.forEach { food ->
            assertTrue(
                food.keys.containsAll(
                    listOf("mealFoodId", "name", "portion", "nutrition", "isNutritionFixed", "iconId"),
                ),
                "음식 줄의 필드가 빠졌습니다: ${food.keys}",
            )
            val foodName = food["name"] as String
            assertTrue(foodName.isNotBlank() && foodName.length <= 30, "음식 이름은 1~30자여야 합니다: $foodName")
            // 인분은 0.5 단위로 0.5~5.0이다. 두 배 하면 1~10의 정수가 된다.
            val doubledPortion = convertPortion(food) * 2
            assertTrue(
                doubledPortion in 1.0..10.0 && doubledPortion == Math.floor(doubledPortion),
                "인분은 0.5 단위로 0.5~5.0이어야 합니다: ${food["portion"]}",
            )
            assertNull(food["nutrition"], "영양 계산 전에는 줄 영양이 없어야 합니다")
            assertNull(food["iconId"], "영양 계산 전에는 줄 아이콘이 없어야 합니다")
            assertEquals(false, food["isNutritionFixed"])
        }
    }

    // 조회한 음식 줄을 그대로 다시 보낼 요청 줄로 바꾼다. 영양과 아이콘은 직접 고칠 때만 채우므로 넣지 않는다.
    private fun convertFoodBody(food: Map<String, Any?>): Map<String, Any?> = mapOf(
        "mealFoodId" to (food["mealFoodId"] as Number).toLong(),
        "name" to food["name"],
        "portion" to convertPortion(food),
    )

    private fun convertPortion(food: Map<String, Any?>): Double = (food["portion"] as Number).toDouble()

    private fun convertCalory(food: Map<String, Any?>): Int =
        ((food["nutrition"] as Map<*, *>)["calory"] as Number).toInt()

    private fun getMeal(mealId: Long): JsonPath = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v2/meals/$mealId")
    } Then {
        statusCode(200)
        body(
            "keySet()",
            hasItems(
                "mealId", "analysisVersion", "name", "mealAt", "status", "nutrition",
                "imageUrl", "canCreateAnalysisFeedback", "catComment", "iconId", "foods",
            ),
        )
    } Extract {
        jsonPath()
    }

    private fun getDailyMeals(date: LocalDate): List<Map<String, Any?>> = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        queryParams(mapOf("year" to date.year, "month" to date.monthValue, "day" to date.dayOfMonth))
    } When {
        get("/api/v2/meals/daily")
    } Then {
        statusCode(200)
        body("keySet()", hasItems("date", "meals", "dailyNutrition"))
    } Extract {
        jsonPath().getList("meals")
    }

    // 음식 목록을 보내고 응답의 상태를 돌려준다. 새로 구할 값이 있으면 WAITING, 없으면 요청 안에서 끝나 COMPLETED다.
    private fun updateMealFoods(mealId: Long, foods: List<Map<String, Any?>>): String = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        body(mapOf("foods" to foods))
    } When {
        put("/api/v2/meals/$mealId/foods")
    } Then {
        statusCode(200)
        body("id", equalTo(mealId.toInt()))
    } Extract {
        path("status")
    }

    private fun getAnalysisStatus(mealId: Long): String = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v2/meals/$mealId/analysis")
    } Then {
        statusCode(200)
        body("keySet()", hasItems("id", "status"))
    } Extract {
        path("status")
    }

    // 상태가 [statuses] 중 하나가 될 때까지 상태 조회 API를 폴링한다. 시간 안에 되지 않으면 마지막 상태를 돌려준다.
    private fun waitForStatus(mealId: Long, statuses: Set<String>): String {
        val deadline = System.currentTimeMillis() + ANALYSIS_WAIT_TIMEOUT_MILLIS
        var status = getAnalysisStatus(mealId)
        while (status !in statuses && System.currentTimeMillis() < deadline) {
            Thread.sleep(ANALYSIS_POLL_INTERVAL_MILLIS)
            status = getAnalysisStatus(mealId)
        }
        return status
    }

    // 오늘의 기록 시도 횟수. 삭제한 식사와 실패한 식사도 센다.
    private fun getTodayAttemptCount(): Int = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v1/home")
    } Then {
        statusCode(200)
    } Extract {
        jsonPath().getInt("todayMealSummary.todayAttemptCount")
    }

    // 우편 목록은 최신순이라 첫 우편이 가장 최근 우편이다. 우편이 하나도 없으면 null이다.
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

    companion object {
        // 서버의 lease는 60초이고, 인식·1인분 조회·코멘트는 각각 Gemini를 한 번 부른다. 재시도까지 끝날 시간을 두고 2초마다 묻는다.
        private const val ANALYSIS_WAIT_TIMEOUT_MILLIS = 90_000L
        private const val ANALYSIS_POLL_INTERVAL_MILLIS = 2_000L

        private const val NOT_EXISTING_MEAL_ID = Long.MAX_VALUE
        private const val NOT_EXISTING_ICON_ID = Long.MAX_VALUE
        private const val ADDED_FOOD_NAME = "김치"
        private const val UPDATED_MEAL_NAME = "E2E 점심"

        // 서버는 식사 날짜를 한국 시간 기준으로 나눈다(MealAttemptService.MEAL_ZONE).
        private val MEAL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

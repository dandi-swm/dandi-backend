package com.dandi.nyummy.e2e.meal

import com.dandi.nyummy.e2e.support.AuthFixture
import com.dandi.nyummy.e2e.support.ImageFixture
import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.oneOf
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MealE2eTest {
    @Test
    fun `식사를 기록하고 분석·조회·수정·평가·삭제한다`() {
        val today = LocalDate.now(MEAL_ZONE)
        val summaryBefore = getTodayMealSummary()

        val imageKey = ImageFixture.createUploadedImage("MEAL", "/images/cake-undated.jpeg", "image/jpeg")
        val created = createMeal(imageKey)
        val mealId = created.id
        var rewardBoxMailId: Long? = null

        try {
            // 1. 분석 완료. 분석은 Worker가 따로 하므로 기록 응답은 WAITING이고, 상태 조회 API로 끝날 때까지 기다린다.
            // Gemini 응답은 실행마다 달라 가끔 실패한다. 앱처럼 재시도 API로 다시 요청한다.
            assertEquals("WAITING", created.status)
            var status = waitForAnalysis(mealId)
            var retryCount = 0
            while (status == "FAILED" && retryCount < MAX_ANALYSIS_RETRY_COUNT) {
                assertEquals("WAITING", retryNutritionAnalysis(mealId))
                status = waitForAnalysis(mealId)
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

            // 3. 오늘의 일일 목록·월간 캘린더·홈·우편함에 반영
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

            // 기록 보상 상자는 식사와 같은 트랜잭션에서 만들어지므로 가장 최근 우편이다.
            // 생성 응답에 우편 id가 없어 등급 번호로 같은 상자인지 확인한다.
            rewardBoxMailId = Given {
                spec(RequestSpecs.createAuthorizedSpec())
                queryParam("size", 1)
            } When {
                get("/api/v1/mails")
            } Then {
                statusCode(200)
                body("mails[0].category", equalTo("REWARD_BOX"))
                body("mails[0].imageId", equalTo(REWARD_BOX_IDS.getValue(created.rewardBoxCategory)))
                body("mails[0].isClaimed", equalTo(false))
                body("mails[0].expiresAt", notNullValue())
                // X-Language 없이 기록했으므로 우편 문구는 예전 앱 빌드가 받던 한국어 그대로다
                body("mails[0].title", oneOf("브론즈 상자", "실버 상자", "골드 상자"))
                body("mails[0].content", equalTo("식사 기록 보상"))
            } Extract {
                jsonPath().getLong("mails[0].mailId")
            }

            val summaryAfterCreate = getTodayMealSummary()
            assertEquals(summaryBefore.recordedCount + 1, summaryAfterCreate.recordedCount, "홈의 오늘 식사 수")
            assertEquals(summaryBefore.attemptCount + 1, summaryAfterCreate.attemptCount, "홈의 오늘 기록 시도 횟수")
            // 고정 계정이 어제까지 이어 왔는지는 실행마다 달라 정확한 값은 단정하지 않는다. 오늘 기록했으니 1일 이상이다.
            assertTrue(getStreakDays() >= 1, "식사를 기록한 날인데 홈의 연속 기록 일수가 0입니다")

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

            // 5. 영양 수정: 보낸 항목만 바뀌고, 생략한 항목과 이름은 그대로다
            val analyzedCalory = meal.getInt("nutrition.calory")
            val analyzedCarbs = meal.getInt("nutrition.carbs")
            val analyzedProtein = meal.getInt("nutrition.protein")
            val analyzedFat = meal.getInt("nutrition.fat")
            // 분석한 값과 반드시 다르면서 허용 범위(0~999) 안에 드는 값이다.
            val updatedCarbs = (analyzedCarbs + 1) % 1000
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("carbs" to updatedCarbs))
            } When {
                patch("/api/v1/meals/$mealId/nutrition")
            } Then {
                statusCode(200)
                body(
                    "keySet()",
                    hasItems(
                        "mealId", "name", "mealAt", "status", "nutrition",
                        "imageUrl", "canCreateAnalysisFeedback", "catComment", "iconId",
                    ),
                )
                body("nutrition.calory", equalTo(analyzedCalory))
                body("nutrition.carbs", equalTo(updatedCarbs))
                body("nutrition.protein", equalTo(analyzedProtein))
                body("nutrition.fat", equalTo(analyzedFat))
                body("name", equalTo(updatedName))
            }

            // 범위를 벗어난 항목이 하나라도 있으면 거부한다. 함께 보낸 정상 항목(protein)도 저장되면 안 된다.
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(mapOf("calory" to -1, "protein" to (analyzedProtein + 1) % 1000))
            } When {
                patch("/api/v1/meals/$mealId/nutrition")
            } Then {
                statusCode(400)
                body("code", equalTo("api.common.invalidInputValue"))
            }

            // 남의 식사는 수정할 수 없다.
            Given {
                spec(RequestSpecs.createAuthorizedSpec(AuthFixture.otherAccessToken))
                body(mapOf("fat" to (analyzedFat + 1) % 1000))
            } When {
                patch("/api/v1/meals/$mealId/nutrition")
            } Then {
                statusCode(403)
                body("code", equalTo("api.auth.forbidden"))
            }

            // 거부된 두 요청이 값을 바꾸지 않았는지, 수정한 값이 상세와 일일 목록에 반영됐는지 확인한다.
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/meals/$mealId")
            } Then {
                statusCode(200)
                body("nutrition.calory", equalTo(analyzedCalory))
                body("nutrition.carbs", equalTo(updatedCarbs))
                body("nutrition.protein", equalTo(analyzedProtein))
                body("nutrition.fat", equalTo(analyzedFat))
            }
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                queryParams(mapOf("year" to today.year, "month" to today.monthValue, "day" to today.dayOfMonth))
            } When {
                get("/api/v1/meals/daily")
            } Then {
                statusCode(200)
                body("meals.find { it.mealId == $mealId }.calory", equalTo(analyzedCalory))
                body("meals.find { it.mealId == $mealId }.carbs", equalTo(updatedCarbs))
                body("meals.find { it.mealId == $mealId }.protein", equalTo(analyzedProtein))
                body("meals.find { it.mealId == $mealId }.fat", equalTo(analyzedFat))
            }

            // 6. 분석 평가: 식사당 한 번만, 평가 후에는 평가 가능 여부가 꺼진다
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

            // 7. 삭제: 상세는 404, 홈의 식사 수는 돌아오지만 기록 시도 횟수는 돌아오지 않는다
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
            // 식사를 삭제해도 그날 기록한 것은 연속 기록으로 인정한다.
            assertTrue(getStreakDays() >= 1, "식사를 삭제했더니 홈의 연속 기록 일수가 0이 되었습니다")
        } finally {
            // 중간에 실패하면 이미 삭제된 상태일 수 있다(그때는 404). 정리는 결과를 검증하지 않는다.
            Given { spec(RequestSpecs.createAuthorizedSpec()) } When { delete("/api/v1/meals/$mealId") }
            // 기록 보상 상자는 받은 우편만 삭제할 수 있어 받은 뒤 지운다. 이미 받았거나 지웠을 수 있어 결과는 검증하지 않는다.
            // 고정 계정 코인이 상자만큼 늘어나고 되돌릴 수 없다(코인을 쓰는 API가 없고, 어드민 API는 ALB 밖이라 닿지 않는다).
            // 받기·삭제·복구 자체는 MailE2eTest가 확인한다.
            rewardBoxMailId?.let { mailId ->
                Given { spec(RequestSpecs.createAuthorizedSpec()) } When { post("/api/v1/mails/$mailId/claim") }
                Given { spec(RequestSpecs.createAuthorizedSpec()) } When { delete("/api/v1/mails/$mailId") }
            }
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

    // 고정 계정은 튜토리얼 배포 전에 가입해 마이그레이션이 완료로 채웠으므로 항상 거부된다.
    // 성공 경로는 사용자당 한 번뿐이라 고정 계정으로 반복해 돌릴 수 없어 단위 테스트(MealServiceTest)가 맡는다.
    @Test
    fun `이미 튜토리얼을 마친 사용자는 튜토리얼 식사를 다시 만들 수 없다`() {
        val latestMailIdBefore = getLatestMailId()

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            post("/api/v1/meals/tutorial")
        } Then {
            statusCode(409)
            body("code", equalTo("api.meal.tutorialAlreadyCompleted"))
        }

        // 거부됐어도 보상 상자 우편이 먼저 만들어졌을 수 있으므로 가장 최근 우편이 그대로인지 본다.
        assertEquals(latestMailIdBefore, getLatestMailId(), "튜토리얼 거부 후 새 우편이 생겼습니다")
    }

    // 분석은 생성 요청 안에서 동기로 끝나므로 응답의 상태가 최종 결과다.
    // 기록 보상으로 보낸 상자의 등급도 함께 온다. 상자에 든 코인 수는 응답에 없다.
    private fun createMeal(imageKey: String): CreateMealResponse = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        body(mapOf("imageKey" to imageKey))
    } When {
        post("/api/v1/meals")
    } Then {
        statusCode(200)
        body("keySet()", hasItems("id", "status", "rewardBoxCategory"))
        body("status", equalTo("WAITING"))
        body("rewardBoxCategory", oneOf("BRONZE", "SILVER", "GOLD"))
    } Extract {
        val response = jsonPath()
        CreateMealResponse(
            id = response.getLong("id"),
            status = response.getString("status"),
            rewardBoxCategory = response.getString("rewardBoxCategory"),
        )
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

    private fun getStreakDays(): Int = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v1/home")
    } Then {
        statusCode(200)
    } Extract {
        path("streak.streakDays")
    }

    private fun retryNutritionAnalysis(mealId: Long): String = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        post("/api/v1/meals/$mealId/analysis")
    } Then {
        statusCode(200)
    } Extract {
        path("status")
    }

    private fun getAnalysisStatus(mealId: Long): String = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v1/meals/$mealId/analysis")
    } Then {
        statusCode(200)
        body("keySet()", hasItems("id", "status"))
    } Extract {
        path("status")
    }

    // 분석이 COMPLETED나 FAILED로 끝날 때까지 상태 조회 API를 폴링한다. 시간 안에 끝나지 않으면 마지막 상태를 돌려준다.
    private fun waitForAnalysis(mealId: Long): String {
        val deadline = System.currentTimeMillis() + ANALYSIS_WAIT_TIMEOUT_MILLIS
        var status = getAnalysisStatus(mealId)
        while (status !in FINISHED_ANALYSIS_STATUSES && System.currentTimeMillis() < deadline) {
            Thread.sleep(ANALYSIS_POLL_INTERVAL_MILLIS)
            status = getAnalysisStatus(mealId)
        }
        return status
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

    private data class TodayMealSummary(val recordedCount: Int, val attemptCount: Int)

    // 생성 API의 응답(id, status, rewardBoxCategory). 서버 DTO 이름과 맞춘다.
    private data class CreateMealResponse(val id: Long, val status: String, val rewardBoxCategory: String)

    companion object {
        // 첫 분석을 포함해 최대 3번 시도한다. 그래도 실패하면 일시적 흔들림이 아니라 실제 문제로 본다.
        private const val MAX_ANALYSIS_RETRY_COUNT = 2

        // 서버의 lease는 60초이고 Gemini 응답은 p95 약 13초다. 재시도까지 끝날 시간을 두고 2초마다 묻는다.
        private const val ANALYSIS_WAIT_TIMEOUT_MILLIS = 90_000L
        private const val ANALYSIS_POLL_INTERVAL_MILLIS = 2_000L
        private val FINISHED_ANALYSIS_STATUSES = setOf("COMPLETED", "FAILED")

        // 앱이 상자 이미지 경로를 조립하는 번호. 서버의 RewardBoxCategory.id와 같은 약속이다.
        private val REWARD_BOX_IDS = mapOf("BRONZE" to 1, "SILVER" to 2, "GOLD" to 3)

        // 서버는 식사 날짜를 한국 시간 기준으로 나눈다(MealAttemptService.MEAL_ZONE).
        private val MEAL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

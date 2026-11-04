package com.dandi.nyummy.e2e.user

import com.dandi.nyummy.e2e.support.E2eProperties
import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.path.json.JsonPath
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UserE2eTest {
    @Smoke
    @Test
    fun `본인 프로필을 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/users/me")
        } Then {
            statusCode(200)
            // 고정 테스트 계정의 토큰으로 조회했는지 확인한다.
            body("email", equalTo(E2eProperties.email))
            // 앱이 읽는 필드가 이름 그대로 있는지 확인한다. 필드가 추가되는 건 허용하고, 빠지거나 이름이 바뀌면 실패한다.
            body(
                "keySet()",
                hasItems(
                    "id", "email", "nickname", "birth", "gender", "height", "weight",
                    "coin", "breakfastHour", "lunchHour", "dinnerHour",
                ),
            )
        }
    }

    @Test
    fun `프로필을 바꾸면 보낸 항목만 바뀐다`() {
        val before = getProfile()
        // 생략 = 유지라 원래 null이던 항목은 되돌릴 수 없다. 바꾸기 전에 멈춰 고정 계정을 망가뜨리지 않는다.
        val nickname = assertNotNull(before.nickname, "고정 계정의 닉네임이 비어 있어 되돌릴 수 없습니다")
        val weight = assertNotNull(before.weight, "고정 계정의 몸무게가 비어 있어 되돌릴 수 없습니다")
        // 현재 값과 같은 값으로 바꾸면 서버가 아무것도 안 해도 통과하므로, 현재 값과 다른 값을 고른다.
        val requested = before.copy(
            nickname = if (nickname == FIRST_NICKNAME) SECOND_NICKNAME else FIRST_NICKNAME,
            weight = weight + 1,
        )

        try {
            updateProfile(mapOf("nickname" to requested.nickname, "weight" to requested.weight)).statusCode(200)
            // 생년월일 · 성별 · 키는 보내지 않았으므로 그대로여야 한다.
            assertEquals(requested, getProfile())
        } finally {
            updateProfile(mapOf("nickname" to nickname, "weight" to weight))
        }
    }

    @Test
    fun `프로필 값이 가입 검증에 어긋나면 거부하고 기존 값을 유지한다`() {
        val before = getProfile()

        listOf(
            mapOf("height" to 0),
            mapOf("nickname" to "  "),
            mapOf("birth" to "2999-01-01"),
        ).forEach { body ->
            updateProfile(body).statusCode(400).body("code", equalTo("api.common.invalidInputValue"))
        }

        assertEquals(before, getProfile(), "거부된 요청이 프로필을 바꿨습니다")
    }

    // 마케팅 알림은 건드리지 않는다. 거부 → 동의로 바뀌면 동의 시각(marketingAgreedAt)이 기록되고,
    // 그동안 리텐션 푸시 대상이 된다.
    @Test
    fun `서비스 알림 수신 설정을 바꾸면 보낸 항목만 바뀐다`() {
        val before = getPushSetting()
        val isServicePushEnabled = before.getBoolean("isServicePushEnabled")
        val isMarketingPushEnabled = before.getBoolean("isMarketingPushEnabled")

        try {
            updateServicePushSetting(!isServicePushEnabled)

            val after = getPushSetting()
            // PATCH는 보낸 항목만 바꾸고 생략한 항목은 유지한다.
            assertEquals(!isServicePushEnabled, after.getBoolean("isServicePushEnabled"))
            assertEquals(isMarketingPushEnabled, after.getBoolean("isMarketingPushEnabled"))
        } finally {
            // 고정 계정이라 다음 실행이 같은 상태에서 시작하도록 원래 값으로 되돌린다.
            updateServicePushSetting(isServicePushEnabled)
        }
    }

    @Test
    fun `끼니 시각을 세 끼니 한 번에 교체한다`() {
        val before = getMealHours()
        // 현재 값과 같은 값으로 바꾸면 서버가 아무것도 안 해도 통과하므로, 현재 값과 다른 후보를 고른다.
        val requested = if (before == FIRST_MEAL_HOURS) SECOND_MEAL_HOURS else FIRST_MEAL_HOURS

        try {
            updateMealHours(requested)
            assertEquals(requested, getMealHours())
        } finally {
            updateMealHours(before)
        }
    }

    @Test
    fun `끼니 시각이 겹치면 거부하고 기존 값을 유지한다`() {
        val before = getMealHours()

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(createMealHoursBody(MealHours(breakfastHour = 12, lunchHour = 12, dinnerHour = 19)))
        } When {
            put("/api/v1/users/me/meal-time")
        } Then {
            statusCode(400)
            body("code", equalTo("api.common.invalidInputValue"))
        }

        assertEquals(before, getMealHours())
    }

    // 프로필은 GET /me 응답에 들어 있다. 날짜 · enum은 앱이 받는 문자열 그대로 비교한다.
    private fun getProfile(): Profile {
        val me = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/users/me")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath()
        }
        return Profile(
            nickname = me.getString("nickname"),
            birth = me.getString("birth"),
            gender = me.getString("gender"),
            height = me.get<Int?>("height"),
            weight = me.get<Int?>("weight"),
        )
    }

    private fun updateProfile(body: Map<String, Any?>): ValidatableResponse = Given {
        spec(RequestSpecs.createAuthorizedSpec())
        body(body)
    } When {
        patch("/api/v1/users/me")
    } Then {
        // 응답 검증은 호출하는 쪽에서 한다
    }

    private fun getPushSetting(): JsonPath = Given {
        spec(RequestSpecs.createAuthorizedSpec())
    } When {
        get("/api/v1/users/me/push")
    } Then {
        statusCode(200)
        body("keySet()", hasItems("isServicePushEnabled", "isMarketingPushEnabled"))
    } Extract {
        jsonPath()
    }

    private fun updateServicePushSetting(isEnabled: Boolean) {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("isServicePushEnabled" to isEnabled))
        } When {
            patch("/api/v1/users/me/push")
        } Then {
            statusCode(200)
        }
    }

    // 끼니 시각은 GET /me 응답에 들어 있다.
    private fun getMealHours(): MealHours {
        val me = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/users/me")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath()
        }
        return MealHours(
            breakfastHour = me.get<Int?>("breakfastHour"),
            lunchHour = me.get<Int?>("lunchHour"),
            dinnerHour = me.get<Int?>("dinnerHour"),
        )
    }

    private fun updateMealHours(mealHours: MealHours) {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(createMealHoursBody(mealHours))
        } When {
            put("/api/v1/users/me/meal-time")
        } Then {
            statusCode(200)
        }
    }

    // 요청 필드 이름도 계약이라 data class를 그대로 직렬화하지 않고 키를 문자 그대로 적는다.
    private fun createMealHoursBody(mealHours: MealHours): Map<String, Int?> = mapOf(
        "breakfastHour" to mealHours.breakfastHour,
        "lunchHour" to mealHours.lunchHour,
        "dinnerHour" to mealHours.dinnerHour,
    )

    private data class MealHours(val breakfastHour: Int?, val lunchHour: Int?, val dinnerHour: Int?)

    private data class Profile(
        val nickname: String?,
        val birth: String?,
        val gender: String?,
        val height: Int?,
        val weight: Int?,
    )

    companion object {
        private const val FIRST_NICKNAME = "E2E집사"
        private const val SECOND_NICKNAME = "E2E냥집사"

        // null인 끼니는 리마인더를 받지 않는다. 두 후보 모두 null을 하나씩 넣어 null 저장도 확인한다.
        private val FIRST_MEAL_HOURS = MealHours(breakfastHour = null, lunchHour = 12, dinnerHour = 19)
        private val SECOND_MEAL_HOURS = MealHours(breakfastHour = 8, lunchHour = 13, dinnerHour = null)
    }
}

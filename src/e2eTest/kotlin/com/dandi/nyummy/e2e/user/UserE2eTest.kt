package com.dandi.nyummy.e2e.user

import com.dandi.nyummy.e2e.support.E2eProperties
import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test

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
}

package com.dandi.nyummy.e2e

import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class HealthE2eTest {
    // health 상태에는 DB·Redis 연결이 합산되므로 인프라 연결 확인의 첫 단계가 된다.
    @Smoke
    @Test
    fun `서버가 UP 상태다`() {
        Given {
            spec(RequestSpecs.base)
        } When {
            get("/actuator/health")
        } Then {
            statusCode(200)
            body("status", equalTo("UP"))
        }
    }
}

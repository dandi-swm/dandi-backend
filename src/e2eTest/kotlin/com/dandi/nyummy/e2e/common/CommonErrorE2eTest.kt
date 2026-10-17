package com.dandi.nyummy.e2e.common

import com.dandi.nyummy.e2e.support.E2eProperties
import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test

// 앱은 상태 코드와 응답의 code로 에러를 구분한다. 에러 응답 형식도 계약이다.
class CommonErrorE2eTest {
    @Test
    fun `토큰 없이 인증이 필요한 API를 호출하면 401이다`() {
        Given {
            spec(RequestSpecs.base)
        } When {
            get("/api/v1/users/me")
        } Then {
            statusCode(401)
            body("keySet()", hasItems("code", "message"))
            body("code", equalTo("api.auth.unauthorized"))
        }
    }

    @Test
    fun `형식이 잘못된 토큰이면 401이다`() {
        Given {
            spec(RequestSpecs.base)
            header("Authorization", "Bearer invalid.e2e.token")
        } When {
            get("/api/v1/users/me")
        } Then {
            statusCode(401)
            body("code", equalTo("api.auth.unauthorized"))
        }
    }

    @Test
    fun `없는 경로는 404다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/e2e-not-exists")
        } Then {
            statusCode(404)
            body("code", equalTo("api.common.notFound"))
        }
    }

    // 역직렬화 단계에서 실패하므로 설정은 바뀌지 않는다. 생성 API 대신 PATCH를 써서 실수로 리소스가 생길 여지를 없앤다.
    @Test
    fun `깨진 JSON 바디는 400이다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body("""{ "isServicePushEnabled": """)
        } When {
            patch("/api/v1/users/me/push")
        } Then {
            statusCode(400)
            body("code", equalTo("api.common.invalidInputValue"))
        }
    }

    @Test
    fun `비밀번호가 틀리면 로그인에 실패한다`() {
        Given {
            spec(RequestSpecs.base)
            body(mapOf("email" to E2eProperties.email, "password" to WRONG_PASSWORD))
        } When {
            post("/api/v1/auth/login")
        } Then {
            statusCode(401)
            body("code", equalTo("api.auth.invalidCredentials"))
        }
    }

    companion object {
        // 실패하면 요청 바디가 로그에 남지만 진짜 비밀번호가 아니라서 괜찮다.
        private const val WRONG_PASSWORD = "e2e-wrong-password-1"
    }
}

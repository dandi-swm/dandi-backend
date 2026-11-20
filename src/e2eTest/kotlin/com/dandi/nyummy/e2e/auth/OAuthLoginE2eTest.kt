package com.dandi.nyummy.e2e.auth

import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.Response
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

// 진짜 소셜 토큰은 E2E에서 만들 수 없어 거부 경로만 본다. 성공 경로는 src/test/http/auth.http로 확인한다.
// 소셜 로그인은 토큰을 검증하기 전에 아무것도 쓰지 않고, 신규 회원이어도 사용자를 만들지 않는다.
// 그래서 거부 테스트에서 따로 확인할 부작용이 없다.
class OAuthLoginE2eTest {
    // Apple 로그인이 없는 이미지가 배포되면 400 unsupportedOAuthProvider가 와서 이 테스트가 실패한다.
    // 번들 ID 같은 설정 값이 빠진 것은 잡지 못한다. 형식이 틀린 토큰은 aud를 보기 전에 거절되기 때문이다.
    @Smoke
    @Test
    fun `Apple 로그인은 형식이 잘못된 ID 토큰이면 401이다`() {
        requestOAuthLogin(
            mapOf(
                "provider" to "APPLE",
                "token" to "invalid.e2e.token",
                "nonce" to "e2e-nonce",
            ),
        ) Then {
            statusCode(401)
            body("code", equalTo("api.auth.invalidOAuthToken"))
        }
    }

    @Test
    fun `Apple 로그인은 nonce가 없으면 400이다`() {
        requestOAuthLogin(
            mapOf(
                "provider" to "APPLE",
                "token" to "invalid.e2e.token",
            ),
        ) Then {
            statusCode(400)
            body("code", equalTo("api.auth.oauthNonceRequired"))
        }
    }

    private fun requestOAuthLogin(body: Map<String, String>): Response = Given {
        spec(RequestSpecs.base)
        body(body)
    } When {
        post("/api/v1/auth/oauth/login")
    }
}

package com.dandi.nyummy.e2e.auth

import com.dandi.nyummy.e2e.support.AuthFixture
import com.dandi.nyummy.e2e.support.E2eProperties
import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.Response
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import kotlin.test.assertNotEquals

// 고정 계정으로 로그아웃하므로 다른 테스트와 동시에 돌면 안 된다(공용 AccessToken이 끊긴다).
// 병렬 실행을 켜면 @Isolated를 붙인다.
class AuthSessionE2eTest {
    @Smoke
    @Test
    fun `refresh는 토큰을 교체하고 logout은 세션과 기존 토큰을 끊는다`() {
        // 세션은 사용자당 하나라 새로 로그인하면 공용 토큰의 세션을 대체한다.
        // 공용 AccessToken 자체는 로그아웃 전까지 유효하다.
        val loginRefreshToken = AuthFixture.login(E2eProperties.email, E2eProperties.password)
            .getString("refreshToken")

        try {
            // JWT에 고유 ID가 없어 같은 초에 발급하면 교체한 토큰이 이전 토큰과 같아진다. iat가 달라지도록 기다린다.
            waitForNextSecond()

            // 재발급: 새 토큰 쌍을 받고 RefreshToken이 실제로 바뀐다
            val refreshed = requestRefresh(loginRefreshToken) Then {
                statusCode(200)
                body("keySet()", hasItems("accessToken", "refreshToken"))
            } Extract {
                jsonPath()
            }
            val accessToken = refreshed.getString("accessToken")
            val refreshToken = refreshed.getString("refreshToken")
            assertNotEquals(loginRefreshToken, refreshToken)

            // 교체된 이전 RefreshToken은 다시 쓸 수 없다
            requestRefresh(loginRefreshToken) Then {
                statusCode(401)
                body("code", equalTo("api.auth.invalidRefreshToken"))
            }

            // 새 AccessToken으로 인증된다
            Given {
                spec(RequestSpecs.createAuthorizedSpec(accessToken))
            } When {
                get("/api/v1/users/me")
            } Then {
                statusCode(200)
                body("email", equalTo(E2eProperties.email))
            }

            // 무효화 기준 시각이 초 단위라 같은 초에 발급된 토큰은 끊기지 않는다. 발급 시각보다 늦게 로그아웃한다.
            waitForNextSecond()

            Given {
                spec(RequestSpecs.createAuthorizedSpec(accessToken))
            } When {
                post("/api/v1/auth/logout")
            } Then {
                statusCode(204)
            }

            // 로그아웃 후 기존 AccessToken은 거부된다(Redis 무효화 기록).
            // Redis 쓰기가 실패해도 로그아웃은 204를 주므로, 이 확인이 없으면 실패를 모른다.
            Given {
                spec(RequestSpecs.createAuthorizedSpec(accessToken))
            } When {
                get("/api/v1/users/me")
            } Then {
                statusCode(401)
                body("code", equalTo("api.auth.unauthorized"))
            }

            // 로그아웃 후 RefreshToken으로 재발급할 수 없다(DB 세션 삭제)
            requestRefresh(refreshToken) Then {
                statusCode(401)
                body("code", equalTo("api.auth.invalidRefreshToken"))
            }
        } finally {
            // 로그아웃으로 공용 AccessToken도 끊겼으므로 다음 테스트가 다시 로그인하게 한다.
            // 로그아웃 전에 실패했어도 다음 요청이 한 번 더 로그인할 뿐이다.
            AuthFixture.resetAccessToken()
        }
    }

    private fun requestRefresh(refreshToken: String): Response = Given {
        spec(RequestSpecs.base)
        body(mapOf("refreshToken" to refreshToken))
    } When {
        post("/api/v1/auth/refresh")
    }

    // JWT iat와 무효화 기준 시각은 초 단위다. 1초를 넘겨 기다려야 다음 발급·기록이 다른 초가 된다.
    private fun waitForNextSecond() = Thread.sleep(1_100)
}

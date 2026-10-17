package com.dandi.nyummy.e2e.support

import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.When
import io.restassured.path.json.JsonPath

object AuthFixture {
    private var cachedAccessToken: String? = null

    // 고정 테스트 계정의 AccessToken. 실행 중 한 번 로그인해 모든 테스트가 같이 쓴다.
    // AccessToken 수명이 30분이라 전체 실행도 그 안에 끝나야 한다.
    val accessToken: String
        @Synchronized get() = cachedAccessToken
            ?: login(E2eProperties.email, E2eProperties.password).getString("accessToken")
                .also { cachedAccessToken = it }

    // 남의 리소스 접근(403) 확인용. 이 계정은 로그아웃하지 않으므로 한 번만 로그인한다.
    val otherAccessToken: String by lazy {
        login(E2eProperties.otherEmail, E2eProperties.otherPassword).getString("accessToken")
    }

    // 로그아웃은 그 시점 이전에 발급된 AccessToken을 모두 끊는다. 고정 계정으로 로그아웃한 테스트는
    // 끝날 때 이걸 호출해, 다음 요청이 다시 로그인해 새 토큰을 받게 한다.
    @Synchronized
    fun resetAccessToken() {
        cachedAccessToken = null
    }

    // 로그인 응답(accessToken, refreshToken)을 그대로 돌려준다. 세션 테스트처럼 토큰 쌍이 필요할 때 직접 쓴다.
    fun login(email: String, password: String): JsonPath {
        val response = Given {
            spec(RequestSpecs.base)
            body(mapOf("email" to email, "password" to password))
        } When {
            post("/api/v1/auth/login")
        } Extract {
            response()
        }

        // Then으로 검증하면 실패할 때 요청 바디(비밀번호)까지 로그에 남는다. 상태 코드를 직접 확인한다.
        check(response.statusCode == 200) {
            "E2E 계정($email) 로그인 실패: status=${response.statusCode} body=${response.body.asString()}"
        }
        return response.jsonPath()
    }
}

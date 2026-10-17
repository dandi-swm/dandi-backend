package com.dandi.nyummy.e2e.support

import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.When

// 고정 테스트 계정의 AccessToken. 실행 한 번에 한 번만 로그인해 모든 테스트가 같이 쓴다.
// AccessToken 수명이 30분이라 전체 실행도 그 안에 끝나야 한다.
object AuthFixture {
    val accessToken: String by lazy { createAccessToken(E2eProperties.email, E2eProperties.password) }

    // 남의 리소스 접근(403) 확인용. 이 토큰을 쓰는 테스트를 돌릴 때만 로그인한다.
    val otherAccessToken: String by lazy { createAccessToken(E2eProperties.otherEmail, E2eProperties.otherPassword) }

    private fun createAccessToken(email: String, password: String): String {
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
        return response.path("accessToken")
    }
}

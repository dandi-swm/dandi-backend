package com.dandi.nyummy.e2e.support

import io.restassured.builder.RequestSpecBuilder
import io.restassured.config.EncoderConfig
import io.restassured.config.LogConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification

// 모든 E2E 요청이 공유하는 기본 형태(대상 서버, 공통 헤더, 로그·인코딩 설정).
object RequestSpecs {
    // main의 CommonHeader 상수는 의존하지 않으므로 이름을 직접 적는다.
    private const val APP_VERSION = "X-App-Version"
    private const val DEVICE_MODEL = "X-Device-Model"
    private const val OS_VERSION = "X-OS-Version"
    private const val LANGUAGE = "X-Language"
    private const val REGION = "X-Region"
    private const val TIMEZONE_ID = "X-Timezone-Id"

    private val config: RestAssuredConfig = RestAssuredConfig.config()
        // 검증에 실패한 요청만 요청·응답을 남긴다. Jenkins 콘솔에 토큰이 찍히지 않게 Authorization은 가린다.
        .logConfig(
            LogConfig.logConfig()
                .enableLoggingOfRequestAndResponseIfValidationFails()
                .blacklistHeader("Authorization"),
        )
        // 문의 제목, 식사 이름 같은 한글 바디가 깨지지 않게 문자셋을 고정한다.
        .encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset("UTF-8"))

    // 앱이 모든 요청에 싣는 공통 헤더를 똑같이 보낸다.
    // 값은 e2e로 둬서 요청 로그와 문의(inquiries.app_version)에서 E2E 요청을 구분한다.
    val base: RequestSpecification by lazy {
        RequestSpecBuilder()
            .setBaseUri(E2eProperties.baseUrl)
            .setConfig(config)
            .setContentType(ContentType.JSON)
            .addHeaders(
                mapOf(
                    APP_VERSION to "e2e",
                    DEVICE_MODEL to "e2e-runner",
                    OS_VERSION to "e2e",
                    LANGUAGE to "ko",
                    REGION to "KR",
                    TIMEZONE_ID to "Asia/Seoul",
                ),
            )
            .build()
    }
}

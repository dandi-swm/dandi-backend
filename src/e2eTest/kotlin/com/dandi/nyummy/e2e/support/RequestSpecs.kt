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
    //
    // X-Language는 넣지 않는다. REST Assured는 같은 이름의 헤더를 덮어쓰지 않고 하나 더 붙이고, 서버는 첫 값을 읽는다.
    // 여기에 두면 테스트가 붙인 언어가 무시되고, "헤더 없음"(이전 앱 빌드) 요청도 만들 수 없다.
    // 언어를 보는 테스트가 필요할 때만 직접 붙인다.
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
                    REGION to "KR",
                    TIMEZONE_ID to "Asia/Seoul",
                ),
            )
            .build()
    }

    // 기본은 고정 테스트 계정. 남의 리소스 접근을 확인할 때는 AuthFixture.otherAccessToken을 넘긴다.
    fun createAuthorizedSpec(accessToken: String = AuthFixture.accessToken): RequestSpecification = RequestSpecBuilder()
        .addRequestSpecification(base)
        .addHeader("Authorization", "Bearer $accessToken")
        .build()
}

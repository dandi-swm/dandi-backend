package com.dandi.nyummy.e2e.support

import io.restassured.config.EncoderConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems

// 도메인 생성 API(문의·식사)에 넘길 이미지를 앱과 같은 순서로 업로드해 둔다.
object ImageFixture {
    // presigned URL은 서명에 들어간 값과 한 글자라도 다르면 S3가 403으로 거부한다.
    // REST Assured가 Content-Type 뒤에 charset을 붙이지 않게 한다.
    private val uploadConfig: RestAssuredConfig = RestAssuredConfig.config()
        .encoderConfig(EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false))

    /**
     * @param purpose 업로드 용도(INQUIRY, MEAL)
     * @param resourcePath src/e2eTest/resources 아래 이미지 경로
     * @return 도메인 생성 API에 넘길 imageKey
     */
    fun createUploadedImage(purpose: String, resourcePath: String, contentType: String): String {
        val bytes = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "테스트 이미지가 없습니다: $resourcePath"
        }.use { it.readBytes() }

        val upload = Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("purpose" to purpose, "contentType" to contentType, "fileSizeBytes" to bytes.size))
        } When {
            post("/api/v1/images/presigned-url")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("uploadUrl", "imageKey", "uploadMethod", "uploadHeaders", "expiresAt"))
            body("uploadMethod", equalTo("PUT"))
        } Extract {
            jsonPath()
        }

        // 앱도 응답의 uploadHeaders(Content-Type, x-amz-tagging)를 그대로 보낸다. 빠지면 서명이 맞지 않는다.
        // S3로 가는 요청이라 API용 spec(baseUri, Authorization)을 쓰지 않는다.
        val uploadHeaders: Map<String, String> = upload.getMap("uploadHeaders")
        Given {
            config(uploadConfig)
            // uploadUrl은 이미 인코딩된 서명 쿼리를 담고 있다. 다시 인코딩하면 서명이 깨진다.
            urlEncodingEnabled(false)
            headers(uploadHeaders)
            body(bytes)
        } When {
            put(upload.getString("uploadUrl"))
        } Then {
            statusCode(200)
        }

        return upload.getString("imageKey")
    }
}

package com.dandi.nyummy.e2e.image

import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

// 업로드 URL 발급 단계의 거부. 앱이 신고한 값만으로 막으므로 실제 파일은 올리지 않는다.
class ImageE2eTest {
    @Test
    fun `지원하지 않는 형식은 업로드 URL을 발급하지 않는다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(mapOf("purpose" to "INQUIRY", "contentType" to "image/gif", "fileSizeBytes" to 1_000))
        } When {
            post("/api/v1/images/presigned-url")
        } Then {
            statusCode(400)
            body("code", equalTo("api.s3.unsupportedContentType"))
        }
    }

    @Test
    fun `용도별 최대 크기를 넘으면 업로드 URL을 발급하지 않는다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(
                mapOf("purpose" to "INQUIRY", "contentType" to "image/png", "fileSizeBytes" to MAX_FILE_SIZE_BYTES + 1),
            )
        } When {
            post("/api/v1/images/presigned-url")
        } Then {
            statusCode(400)
            body("code", equalTo("api.s3.fileSizeExceeded"))
        }
    }

    companion object {
        // ImagePurpose의 최대 크기(MEAL·INQUIRY 모두 10MB)
        private const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024
    }
}

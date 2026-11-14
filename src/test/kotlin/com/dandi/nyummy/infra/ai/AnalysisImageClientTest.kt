package com.dandi.nyummy.infra.ai

import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.S3ErrorMetadata
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import com.dandi.nyummy.infra.storage.s3.dto.S3ObjectContentResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * S3의 실패가 정한 [AnalysisFailureCategory]로 나뉘는지 다룬다. S3는 mockk로 흉내 낸다.
 */
class AnalysisImageClientTest {

    private val imageKey = "meals/1/2026/11/02/uuid.jpg"

    private val s3StorageClient = mockk<S3StorageClient>()

    private val analysisImageClient = AnalysisImageClient(s3StorageClient)

    private fun assertAnalysisFailure(expected: AnalysisFailureCategory, block: () -> Unit) {
        val exception = assertFailsWith<AnalysisFailureException>(block = block)
        assertEquals(expected, exception.category)
    }

    private fun givenS3Failure(exception: Throwable) {
        coEvery { s3StorageClient.downloadObject(imageKey) } throws exception
    }

    /** 오류 메타데이터를 채우는 SDK API는 내부용이라, 분류에 쓰는 값만 mockk로 흉내 낸다. */
    private fun createS3Exception(
        errorType: ServiceException.ErrorType,
        status: HttpStatusCode,
        isThrottling: Boolean = false,
    ): S3Exception {
        val response = mockk<HttpResponse> { every { this@mockk.status } returns status }
        val metadata = mockk<S3ErrorMetadata> {
            every { this@mockk.errorType } returns errorType
            every { this@mockk.isThrottling } returns isThrottling
            every { protocolResponse } returns response
        }
        return mockk<S3Exception> { every { sdkErrorMetadata } returns metadata }
    }

    // downloadImage

    @Test
    fun `이미지의 바이트와 콘텐츠 타입을 돌려준다`() {
        coEvery { s3StorageClient.downloadObject(imageKey) } returns
            S3ObjectContentResult(bytes = byteArrayOf(1, 2, 3), contentType = "image/jpeg")

        val objectContent = analysisImageClient.downloadImage(imageKey)

        assertContentEquals(byteArrayOf(1, 2, 3), objectContent.bytes)
        assertEquals("image/jpeg", objectContent.contentType)
    }

    @Test
    fun `이미지가 없으면 INVALID_REQUEST로 실패한다`() {
        givenS3Failure(NoSuchKey {})

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_REQUEST) { analysisImageClient.downloadImage(imageKey) }
    }

    @Test
    fun `S3 서버 오류면 UNAVAILABLE로 실패한다`() {
        givenS3Failure(createS3Exception(ServiceException.ErrorType.Server, HttpStatusCode.InternalServerError))

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { analysisImageClient.downloadImage(imageKey) }
    }

    @Test
    fun `S3 요청 한도를 넘으면 UNAVAILABLE로 실패한다`() {
        givenS3Failure(
            createS3Exception(
                ServiceException.ErrorType.Client,
                HttpStatusCode.ServiceUnavailable,
                isThrottling = true,
            ),
        )

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { analysisImageClient.downloadImage(imageKey) }
    }

    @Test
    fun `S3가 요청을 거부하면 INVALID_REQUEST로 실패한다`() {
        givenS3Failure(createS3Exception(ServiceException.ErrorType.Client, HttpStatusCode.BadRequest))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_REQUEST) { analysisImageClient.downloadImage(imageKey) }
    }

    @Test
    fun `S3 권한이 없어 거부하면 MISCONFIGURED로 실패한다`() {
        givenS3Failure(createS3Exception(ServiceException.ErrorType.Client, HttpStatusCode.Forbidden))

        assertAnalysisFailure(AnalysisFailureCategory.MISCONFIGURED) { analysisImageClient.downloadImage(imageKey) }
    }

    @Test
    fun `S3 응답을 받기 전에 실패하면 UNAVAILABLE로 실패한다`() {
        givenS3Failure(SdkBaseException("connect timed out"))

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { analysisImageClient.downloadImage(imageKey) }
    }

    @Test
    fun `이미지가 비었으면 INVALID_REQUEST로 실패한다`() {
        givenS3Failure(IllegalStateException("S3 객체 바디가 비어 있습니다: $imageKey"))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_REQUEST) { analysisImageClient.downloadImage(imageKey) }
    }
}

package com.dandi.nyummy.image.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.image.ExifCaptureTimeReader
import com.dandi.nyummy.image.config.ImageProperties
import com.dandi.nyummy.image.dto.UploadedImage
import com.dandi.nyummy.image.enum.ImagePurpose
import com.dandi.nyummy.image.validator.ImageUploadValidator
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import com.dandi.nyummy.infra.storage.s3.dto.S3ObjectContent
import com.dandi.nyummy.infra.storage.s3.dto.S3UploadResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ImageServiceTest {

    // 2026-10-10 12:00 KST
    private val now = Instant.parse("2026-10-10T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"))
    private val userId = 1L

    private val imageProperties = ImageProperties(
        uploadMethod = "PUT",
        uploadUrlExpirationMinutes = 10,
        imageUrlExpirationMinutes = 10,
    )

    private val s3StorageClient = mockk<S3StorageClient>()
    private val exifCaptureTimeReader = mockk<ExifCaptureTimeReader>()
    private val mealValidator = mockk<ImageUploadValidator> {
        every { purpose } returns ImagePurpose.MEAL
        every { validate(any()) } just Runs
    }

    private val imageService = createImageService(listOf(mealValidator))

    // Tika가 매직 바이트로 판별하므로 헤더만 맞추면 된다.
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16)
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
    private val textBytes = "not an image".toByteArray()

    private val mealKey = "meals/$userId/2026/10/10/uuid.jpg"

    private fun createImageService(validators: List<ImageUploadValidator>) =
        ImageService(s3StorageClient, exifCaptureTimeReader, imageProperties, clock, validators)

    private fun givenPresign() {
        every { s3StorageClient.createPresignedPutUrl(any(), any(), any()) } answers {
            S3UploadResult(
                url = "https://upload.example",
                key = firstArg(),
                uploadHeaders = mapOf("Content-Type" to secondArg(), "x-amz-tagging" to "status=temp"),
            )
        }
    }

    private fun givenUploadedObject(
        key: String = mealKey,
        size: Long = 1000L,
        bytes: ByteArray = jpegBytes,
        capturedAt: Instant? = now,
    ) {
        coEvery { s3StorageClient.getObjectSize(key) } returns size
        coEvery { s3StorageClient.downloadObject(key) } returns S3ObjectContent(bytes, null)
        every { exifCaptureTimeReader.extractCapturedAt(bytes) } returns capturedAt
        coEvery { s3StorageClient.confirmObject(key) } just Runs
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // createUploadUrl

    @Test
    fun `식사 용도면 meals 경로에 날짜와 UUID로 키를 만들고 발급 정보를 반환한다`() {
        givenPresign()

        val result = imageService.createUploadUrl(userId, ImagePurpose.MEAL, "image/jpeg", 1000L)

        assertTrue(
            result.imageKey.matches(Regex("""^meals/1/2026/10/10/[0-9a-f-]{36}\.jpg$""")),
            "키 형식이 맞지 않는다: ${result.imageKey}",
        )
        assertEquals("https://upload.example", result.uploadUrl)
        assertEquals("PUT", result.uploadMethod)
        assertEquals("status=temp", result.uploadHeaders["x-amz-tagging"])
        assertEquals(now.plusSeconds(600).toString(), result.expiresAt)
        verify { s3StorageClient.createPresignedPutUrl(result.imageKey, "image/jpeg", 10.minutes) }
    }

    @Test
    fun `문의 용도면 inquiries 경로로 발급하고 식사 검증기는 실행하지 않는다`() {
        givenPresign()

        val result = imageService.createUploadUrl(userId, ImagePurpose.INQUIRY, "image/png", 1000L)

        assertTrue(result.imageKey.startsWith("inquiries/1/2026/10/10/"), result.imageKey)
        assertTrue(result.imageKey.endsWith(".png"), result.imageKey)
        verify(exactly = 0) { mealValidator.validate(any()) }
    }

    @Test
    fun `용도의 검증기가 거부하면 그 예외를 그대로 던지고 URL을 발급하지 않는다`() {
        every { mealValidator.validate(userId) } throws BusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED)

        assertBusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED) {
            imageService.createUploadUrl(userId, ImagePurpose.MEAL, "image/jpeg", 1000L)
        }
        verify(exactly = 0) { s3StorageClient.createPresignedPutUrl(any(), any(), any()) }
    }

    @Test
    fun `허용하지 않는 MIME 타입이면 발급을 거부한다`() {
        assertBusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE) {
            imageService.createUploadUrl(userId, ImagePurpose.MEAL, "image/gif", 1000L)
        }
        verify(exactly = 0) { s3StorageClient.createPresignedPutUrl(any(), any(), any()) }
    }

    @Test
    fun `신고한 크기가 음수거나 용도의 최대 크기를 넘으면 발급을 거부한다`() {
        val max = ImagePurpose.MEAL.maxFileSizeBytes

        assertBusinessException(S3ErrorCode.FILE_SIZE_EXCEEDED) {
            imageService.createUploadUrl(userId, ImagePurpose.MEAL, "image/jpeg", -1L)
        }
        assertBusinessException(S3ErrorCode.FILE_SIZE_EXCEEDED) {
            imageService.createUploadUrl(userId, ImagePurpose.MEAL, "image/jpeg", max + 1)
        }
    }

    @Test
    fun `신고한 크기가 최대 크기와 같으면 발급한다`() {
        givenPresign()

        imageService.createUploadUrl(userId, ImagePurpose.MEAL, "image/jpeg", ImagePurpose.MEAL.maxFileSizeBytes)

        verify { s3StorageClient.createPresignedPutUrl(any(), "image/jpeg", any()) }
    }

    @Test
    fun `같은 용도에 검증기가 둘이면 생성 시점에 실패한다`() {
        val another = mockk<ImageUploadValidator> { every { purpose } returns ImagePurpose.MEAL }

        assertFailsWith<IllegalArgumentException> {
            createImageService(listOf(mealValidator, another))
        }
    }

    // confirmUpload

    @Test
    fun `모든 검증을 통과하면 콜백을 실행한 뒤 확정한다`() {
        givenUploadedObject()
        val events = mutableListOf<String>()
        coEvery { s3StorageClient.confirmObject(mealKey) } answers { events += "confirm" }

        var received: UploadedImage? = null
        val result = imageService.confirmUpload(userId, ImagePurpose.MEAL, mealKey) {
            received = it
            events += "validate"
        }

        assertEquals(UploadedImage(mealKey, now), result)
        assertEquals(result, received)
        assertEquals(listOf("validate", "confirm"), events)
    }

    @Test
    fun `콜백이 예외를 던지면 확정하지 않는다`() {
        givenUploadedObject()

        assertBusinessException(MealErrorCode.STALE_IMAGE) {
            imageService.confirmUpload(userId, ImagePurpose.MEAL, mealKey) {
                throw BusinessException(MealErrorCode.STALE_IMAGE)
            }
        }
        coVerify(exactly = 0) { s3StorageClient.confirmObject(any()) }
    }

    @Test
    fun `EXIF 촬영 시각이 없으면 capturedAt을 null로 넘긴다`() {
        givenUploadedObject(capturedAt = null)

        val result = imageService.confirmUpload(userId, ImagePurpose.MEAL, mealKey)

        assertNull(result.capturedAt)
        coVerify { s3StorageClient.confirmObject(mealKey) }
    }

    @Test
    fun `다른 사용자의 키면 S3를 조회하지 않고 거부한다`() {
        assertBusinessException(S3ErrorCode.INVALID_KEY) {
            imageService.confirmUpload(userId, ImagePurpose.MEAL, "meals/2/2026/10/10/uuid.jpg")
        }
        coVerify(exactly = 0) { s3StorageClient.getObjectSize(any()) }
    }

    @Test
    fun `다른 용도로 발급된 키면 거부한다`() {
        assertBusinessException(S3ErrorCode.INVALID_KEY) {
            imageService.confirmUpload(userId, ImagePurpose.MEAL, "inquiries/$userId/2026/10/10/uuid.jpg")
        }
        coVerify(exactly = 0) { s3StorageClient.getObjectSize(any()) }
    }

    @Test
    fun `객체가 없으면 OBJECT_NOT_FOUND를 그대로 던진다`() {
        coEvery { s3StorageClient.getObjectSize(mealKey) } throws BusinessException(S3ErrorCode.OBJECT_NOT_FOUND)

        assertBusinessException(S3ErrorCode.OBJECT_NOT_FOUND) {
            imageService.confirmUpload(userId, ImagePurpose.MEAL, mealKey)
        }
    }

    @Test
    fun `실제 크기가 0이거나 최대 크기를 넘으면 확정하지 않는다`() {
        givenUploadedObject(size = 0L)
        assertBusinessException(S3ErrorCode.FILE_SIZE_EXCEEDED) {
            imageService.confirmUpload(userId, ImagePurpose.MEAL, mealKey)
        }

        givenUploadedObject(size = ImagePurpose.MEAL.maxFileSizeBytes + 1)
        assertBusinessException(S3ErrorCode.FILE_SIZE_EXCEEDED) {
            imageService.confirmUpload(userId, ImagePurpose.MEAL, mealKey)
        }

        coVerify(exactly = 0) { s3StorageClient.confirmObject(any()) }
    }

    @Test
    fun `실제 콘텐츠가 이미지가 아니면 확정하지 않는다`() {
        givenUploadedObject(bytes = textBytes)

        assertBusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE) {
            imageService.confirmUpload(userId, ImagePurpose.MEAL, mealKey)
        }
        coVerify(exactly = 0) { s3StorageClient.confirmObject(any()) }
    }

    @Test
    fun `실제 콘텐츠와 키의 확장자가 다르면 확정하지 않는다`() {
        // 키는 .jpg인데 실제로 PNG가 올라온 경우
        givenUploadedObject(bytes = pngBytes)

        assertBusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE) {
            imageService.confirmUpload(userId, ImagePurpose.MEAL, mealKey)
        }
        coVerify(exactly = 0) { s3StorageClient.confirmObject(any()) }
    }

    // createImageUrl

    @Test
    fun `조회 URL은 설정된 만료 시간으로 발급한다`() {
        every { s3StorageClient.createPresignedGetUrl(mealKey, 10.minutes) } returns "https://get.example"

        assertEquals("https://get.example", imageService.createImageUrl(mealKey))
    }
}

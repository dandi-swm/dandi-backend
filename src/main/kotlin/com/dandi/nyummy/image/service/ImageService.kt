package com.dandi.nyummy.image.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.image.ExifCaptureTimeReader
import com.dandi.nyummy.image.config.ImageProperties
import com.dandi.nyummy.image.dto.UploadUrlResponse
import com.dandi.nyummy.image.dto.UploadedImage
import com.dandi.nyummy.image.enum.ImagePurpose
import com.dandi.nyummy.image.service.ImageService.Companion.ALLOWED_CONTENT_TYPES
import com.dandi.nyummy.image.validator.ImageUploadValidator
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import kotlinx.coroutines.runBlocking
import org.apache.tika.Tika
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * 이미지 업로드 URL 발급과 업로드 확정을 담당한다.
 *
 * 용도별 정책(경로, 최대 크기)은 [ImagePurpose]가 가진다. 발급 전 도메인 검증은 [ImageUploadValidator]
 * 구현으로, 확정 전 도메인 검증은 [confirmUpload]의 콜백으로 끼운다. 확정은 HTTP로 노출하지 않고,
 * 각 도메인 서비스가 자신의 생성 흐름 안에서 [confirmUpload]를 호출한다.
 */
@Service
class ImageService(
    private val s3StorageClient: S3StorageClient,
    private val exifCaptureTimeReader: ExifCaptureTimeReader,
    private val imageProperties: ImageProperties,
    private val clock: Clock,
    validators: List<ImageUploadValidator>,
) {
    companion object {
        private val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
        )

        private val MIME_TO_EXTENSION = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
        )
    }

    private val tika = Tika()

    private val validatorsByPurpose: Map<ImagePurpose, ImageUploadValidator> = validators
        .groupBy { it.purpose }
        .mapValues { (purpose, validatorsOfPurpose) ->
            // associateBy는 중복을 조용히 덮어쓰므로, 어느 검증이 빠졌는지 모르게 되는 상황을 기동 시점에 막는다.
            require(validatorsOfPurpose.size == 1) { "ImageUploadValidator가 용도당 하나여야 합니다: $purpose" }
            validatorsOfPurpose.single()
        }

    /**
     * 이미지를 업로드할 수 있는 presigned URL을 발급한다.
     *
     * 객체 키는 `{purpose.prefix}/{userId}/{년}/{월}/{일}/{UUID}.{확장자}` 형식으로 서버가 생성하므로,
     * 클라이언트는 임의의 키에 업로드할 수 없다. 업로드된 객체는 `status=temp` 상태이며,
     * [confirmUpload]로 확정되지 않으면 버킷의 라이프사이클 룰이 정리한다.
     *
     * [fileSizeBytes]는 사전 검증용 값일 뿐 presigned URL에 서명되지 않는다.
     * 실제 업로드된 크기는 [confirmUpload]에서 다시 확인한다.
     *
     * [purpose]에 [ImageUploadValidator] 구현이 있으면 다른 검사보다 먼저 실행하며,
     * 그 검증이 던진 예외는 그대로 전파된다.
     *
     * @param userId 업로드를 요청한 사용자 ID. 객체 키의 소유자 경로로 사용된다
     * @param purpose 업로드 용도. 객체 키 경로와 최대 크기를 정한다
     * @param contentType 업로드할 파일의 MIME 타입. [ALLOWED_CONTENT_TYPES]에 포함된 값만 허용
     * @param fileSizeBytes 클라이언트가 신고한 파일 크기(byte)
     * @return 업로드 URL/메서드/헤더와 이미지 키를 담은 [UploadUrlResponse].
     *   [UploadUrlResponse.uploadHeaders]는 업로드 요청에 그대로 포함해야 하며,
     *   누락하면 서명이 일치하지 않아 업로드가 거부된다
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] contentType이 허용 목록에 없을 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] fileSizeBytes가 음수이거나
     *   [ImagePurpose.maxFileSizeBytes]를 초과할 경우
     */
    fun createUploadUrl(
        userId: Long,
        purpose: ImagePurpose,
        contentType: String,
        fileSizeBytes: Long,
    ): UploadUrlResponse {
        validatorsByPurpose[purpose]?.validate(userId)

        if (contentType !in ALLOWED_CONTENT_TYPES) {
            throw BusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE)
        }

        if (0 > fileSizeBytes || fileSizeBytes > purpose.maxFileSizeBytes) {
            throw BusinessException(S3ErrorCode.FILE_SIZE_EXCEEDED)
        }

        val extension = MIME_TO_EXTENSION.getValue(contentType)
        val today = LocalDate.now(clock)
        val key =
            "${purpose.prefix}/$userId/${today.year}/${today.monthValue}/${today.dayOfMonth}/" +
                "${UUID.randomUUID()}.$extension"

        val expiration = imageProperties.uploadUrlExpirationMinutes.minutes
        val expiresAt = Instant.now(clock).plusSeconds(expiration.inWholeSeconds)

        val upload = s3StorageClient.createPresignedPutUrl(
            objectKey = key,
            contentType = contentType,
            expiration = expiration,
        )

        return UploadUrlResponse(
            uploadUrl = upload.url,
            imageKey = upload.key,
            uploadMethod = imageProperties.uploadMethod,
            uploadHeaders = upload.uploadHeaders,
            expiresAt = expiresAt.toString(),
        )
    }

    /**
     * 업로드된 이미지를 검증한 뒤 확정(`status=committed`)한다.
     *
     * 공통 검증(소유 경로, 존재, 크기, 실제 MIME)을 통과하면 [validate]로 도메인 고유 검증을 실행하고,
     * 그것까지 통과해야만 확정한다. 순서를 이 메서드가 강제하므로, 어느 검증에서 거부되든
     * 객체는 `status=temp`로 남아 라이프사이클 룰이 정리한다.
     *
     * 객체를 다른 경로로 복사하지 않으므로 확정된 키는 인자로 받은 [imageKey]와 동일하다.
     *
     * @param userId 확정을 요청한 사용자 ID. [imageKey]의 소유권 검증에 사용된다
     * @param purpose 업로드 용도. 다른 용도로 발급된 키는 거부된다
     * @param imageKey [createUploadUrl]로 발급받아 업로드에 사용한 객체 키
     * @param validate 도메인 고유 검증. 예외를 던지면 확정하지 않는다
     * @return 확정된 키와 EXIF 촬영 시각을 담은 [UploadedImage]
     * @throws BusinessException [S3ErrorCode.INVALID_KEY] imageKey가 `{purpose.prefix}/{userId}/`로 시작하지 않을 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] imageKey에 해당하는 객체가 S3에 존재하지 않을 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 실제 업로드된 크기가 0이거나
     *   [ImagePurpose.maxFileSizeBytes]를 초과할 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] 실제 콘텐츠에서 감지된 MIME 타입이
     *   허용 목록에 없거나, imageKey의 확장자와 일치하지 않을 경우
     */
    fun confirmUpload(
        userId: Long,
        purpose: ImagePurpose,
        imageKey: String,
        validate: (UploadedImage) -> Unit = {},
    ): UploadedImage = runBlocking {
        // 1. imageKey가 이 용도·이 사용자의 경로인지 확인
        if (!imageKey.startsWith("${purpose.prefix}/$userId/")) {
            throw BusinessException(S3ErrorCode.INVALID_KEY)
        }

        // 2. 실제 업로드된 크기 확인 (객체가 없으면 OBJECT_NOT_FOUND)
        val actualSize = s3StorageClient.getObjectSize(imageKey)

        if (actualSize == 0L || actualSize > purpose.maxFileSizeBytes) {
            throw BusinessException(S3ErrorCode.FILE_SIZE_EXCEEDED)
        }

        // 3. 확장자를 믿지 않고 실제 콘텐츠 앞부분에서 MIME 타입을 감지해 지원 포맷인지 확인 (jpg, png)
        val objectBytes = s3StorageClient.downloadObject(imageKey).bytes

        val detectedExtension = MIME_TO_EXTENSION[tika.detect(objectBytes)]
            ?: throw BusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE)

        // 4. 감지된 확장자가 imageKey의 확장자와 일치하는지 확인 (키와 실제 내용의 불일치 방지)
        if (imageKey.substringAfterLast(".") != detectedExtension) {
            throw BusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE)
        }

        // 5. 도메인 고유 검증. 거부되면 확정 전이므로 객체는 status=temp로 남는다
        val uploadedImage = UploadedImage(
            imageKey = imageKey,
            capturedAt = exifCaptureTimeReader.extractCapturedAt(objectBytes),
        )

        validate(uploadedImage)

        // 6. 모든 검증을 통과했으므로 라이프사이클 정리 대상에서 제외되도록 확정 처리
        s3StorageClient.confirmObject(imageKey)

        uploadedImage
    }

    /**
     * 확정된 이미지를 조회할 수 있는 presigned URL을 발급한다.
     *
     * @param imageKey 조회할 객체 키
     * @return 다운로드용 presigned URL 문자열
     */
    fun createImageUrl(imageKey: String): String =
        s3StorageClient.createPresignedGetUrl(imageKey, imageProperties.imageUrlExpirationMinutes.minutes)
}

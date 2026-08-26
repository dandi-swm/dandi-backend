package com.dandi.nyummy.infra.aws.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectTaggingRequest
import aws.sdk.kotlin.services.s3.model.Tag
import aws.sdk.kotlin.services.s3.model.Tagging
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.content.toByteArray
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.infra.aws.s3.S3Service.Companion.ALLOWED_CONTENT_TYPES
import com.dandi.nyummy.infra.aws.s3.dto.S3ObjectContent
import com.dandi.nyummy.infra.aws.s3.dto.S3UploadResult
import kotlinx.coroutines.runBlocking
import org.apache.tika.Tika
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.*
import kotlin.time.Duration

@Service
class S3Service(
    private val s3Client: S3Client,
    private val clock: Clock,
    @Value("\${AWS_S3_BUCKET_NAME}") private val bucketName: String,
) {
    companion object {
        private const val MEAL_PREFIX = "meals"

        private val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
        )

        private val MIME_TO_EXTENSION = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
        )

        const val TAG_STATUS = "status"
        const val TAG_STATUS_TEMP = "temp"
        const val TAG_STATUS_COMMITTED = "committed"
    }

    private val tika = Tika()

    /**
     * 식사 이미지를 업로드할 수 있는 presigned URL을 발급한다.
     *
     * 객체 키는 `meals/{userId}/{년}/{월}/{일}/{UUID}.{확장자}` 형식으로 서버가 생성하므로,
     * 클라이언트는 임의의 키에 업로드할 수 없다.
     *
     * 이 시점의 객체는 아직 확정되지 않은 상태이므로 `status=temp` 태그가 URL에 함께 서명된다.
     * 따라서 클라이언트는 [S3UploadResult.uploadHeaders]를 업로드 요청에 그대로 포함해야 하며,
     * 하나라도 누락하면 서명이 일치하지 않아 업로드가 거부된다.
     * [confirmUploadedMealImage]로 확정되지 않은 객체는 버킷의 `status=temp`
     * 라이프사이클 룰이 정리하므로, 서버가 따로 삭제하지 않는다.
     *
     * [fileSizeBytes]는 사전 검증용 값일 뿐 presigned URL에 서명되지 않는다.
     * 실제 업로드된 크기는 [confirmUploadedMealImage]에서 다시 확인한다.
     *
     * @param userId 업로드를 요청한 사용자 ID. 객체 키의 소유자 경로로 사용된다
     * @param contentType 업로드할 파일의 MIME 타입. [ALLOWED_CONTENT_TYPES]에 포함된 값만 허용
     * @param fileSizeBytes 클라이언트가 신고한 파일 크기(byte)
     * @param maxFileSizeBytes 허용되는 최대 파일 크기(byte)
     * @param expiration presigned URL의 유효 기간
     * @return 업로드용 presigned URL, 객체 키, 필수 요청 헤더를 담은 [S3UploadResult]
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] contentType이 허용 목록에 없을 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] fileSizeBytes가 음수이거나 maxFileSizeBytes를 초과할 경우
     */
    fun createMealUploadUrl(
        userId: Long,
        contentType: String,
        fileSizeBytes: Long,
        maxFileSizeBytes: Long,
        expiration: Duration,
    ): S3UploadResult {
        if (contentType !in ALLOWED_CONTENT_TYPES) {
            throw BusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE)
        }

        if (0 > fileSizeBytes || fileSizeBytes > maxFileSizeBytes) {
            throw BusinessException(S3ErrorCode.FILE_SIZE_EXCEEDED)
        }

        val extension = MIME_TO_EXTENSION.getValue(contentType)
        val today = LocalDate.now(clock)
        val uuid = UUID.randomUUID()
        val key =
            "$MEAL_PREFIX/$userId/${today.year}/${today.monthValue}/${today.dayOfMonth}/" +
                "$uuid.$extension"

        val url = createPresignedPutUrl(
            objectKey = key,
            contentType = contentType,
            expiration = expiration,
            objectTagging = "$TAG_STATUS=$TAG_STATUS_TEMP",
        )

        val requiredHeaders = mapOf(
            "Content-Type" to contentType,
            "x-amz-tagging" to "${TAG_STATUS}=${TAG_STATUS_TEMP}",
        )

        return S3UploadResult(url, key, requiredHeaders)
    }

    /**
     * 업로드된 객체를 검증한 뒤 상태 태그를 `status=committed`로 바꿔 확정한다.
     *
     * 객체를 다른 경로로 복사하지 않으므로 반환되는 키는 인자로 받은 [imageKey]와 동일하다.
     * 검증에 실패한 객체는 이 메서드에서 삭제하지 않는다. `status=temp` 태그가 그대로 남아
     * 버킷의 라이프사이클 룰이 정리한다.
     *
     * 키가 요청자 소유 경로(`meals/{userId}/`)인지 확인하므로, 다른 사용자가 업로드한 객체를
     * 확정할 수 없다.
     *
     * @param userId 확정을 요청한 사용자 ID. [imageKey]의 소유권 검증에 사용된다
     * @param imageKey [createMealUploadUrl]로 발급받아 업로드에 사용한 객체 키
     * @param maxFileSizeBytes 허용되는 최대 파일 크기(byte)
     * @return 확정된 객체 키 ([imageKey]와 동일한 값)
     * @throws BusinessException [S3ErrorCode.INVALID_KEY] imageKey가 `meals/{userId}/`로 시작하지 않을 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] imageKey에 해당하는 객체가 S3에 존재하지 않을 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 실제 업로드된 크기가 0이거나 maxFileSizeBytes를 초과할 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] 실제 콘텐츠에서 감지된 MIME 타입이
     *   허용 목록에 없거나, imageKey의 확장자와 일치하지 않을 경우
     */
    fun confirmUploadedMealImage(userId: Long, imageKey: String, maxFileSizeBytes: Long): String = runBlocking {
        // 1. imageKey prefix 확인
        if (!imageKey.startsWith("$MEAL_PREFIX/$userId/")) {
            throw BusinessException(S3ErrorCode.INVALID_KEY)
        }

        // 2. 해당 imageKey에 실제로 이미지가 존재하는지 확인
        val head = try {
            s3Client.headObject {
                bucket = bucketName
                key = imageKey
            }
        } catch (e: NotFound) {
            throw BusinessException(S3ErrorCode.OBJECT_NOT_FOUND)
        }

        // 3. 실제 이미지 크기 확인
        val actualSize = head.contentLength ?: 0L

        if (actualSize == 0L || actualSize > maxFileSizeBytes) {
            throw BusinessException(S3ErrorCode.FILE_SIZE_EXCEEDED)
        }

        // 4. 확장자를 믿지 않고 실제 콘텐츠 앞부분에서 MIME 타입을 감지해 지원 포맷인지 확인 (jpg, png)
        val headerBytes = downloadObjectRange(imageKey, "bytes=0-1023")

        val detectedExtension = MIME_TO_EXTENSION[tika.detect(headerBytes)]
            ?: throw BusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE)

        // 5. 감지된 확장자가 imageKey의 확장자와 일치하는지 확인 (키와 실제 내용의 불일치 방지)
        if (imageKey.substringAfterLast(".") != detectedExtension) {
            throw BusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE)
        }

        // 6. 검증을 통과했으므로 라이프사이클 정리 대상에서 제외되도록 확정 처리
        markCommitted(imageKey)

        imageKey
    }

    /**
     * 객체를 다운로드할 수 있는 presigned URL을 발급한다.
     *
     * @param key 조회할 S3 객체 키
     * @param duration presigned URL의 유효 기간
     * @return 다운로드용 presigned URL 문자열
     */
    fun createPresignedGetUrl(key: String, duration: Duration): String {
        val request = GetObjectRequest {
            bucket = bucketName
            this.key = key
        }
        return runBlocking { s3Client.presignGetObject(request, duration) }.url.toString()
    }

    /**
     * 객체를 업로드할 수 있는 presigned URL을 발급한다.
     *
     * @param objectKey 업로드될 S3 객체 키
     * @param contentType 업로드할 파일의 MIME 타입
     * @param expiration presigned URL의 유효 기간
     * @param objectTagging 객체에 함께 설정할 태그(`"key=value"` 형식). 지정하면 태그도 서명 대상에
     *   포함되므로, 클라이언트는 동일한 값을 `x-amz-tagging` 헤더로 보내야 업로드가 성공한다
     * @return 업로드용 presigned URL 문자열
     */
    private fun createPresignedPutUrl(
        objectKey: String,
        contentType: String,
        expiration: Duration,
        objectTagging: String? = null,
    ): String {
        val request = PutObjectRequest {
            bucket = bucketName
            key = objectKey
            this.contentType = contentType
            tagging = objectTagging
        }

        return runBlocking { s3Client.presignPutObject(request, expiration) }.url.toString()
    }

    /**
     * 객체의 특정 byte 범위만 다운로드한다.
     *
     * @param key 조회할 S3 객체 키
     * @param byteRange HTTP Range 헤더 형식의 범위 (예: `"bytes=0-1023"`)
     * @return 지정한 범위의 바이트 배열. 응답 바디가 없으면 빈 배열
     */
    private suspend fun downloadObjectRange(key: String, byteRange: String): ByteArray {
        val request = GetObjectRequest {
            bucket = bucketName
            this.key = key
            range = byteRange
        }

        return s3Client.getObject(request) { response ->
            response.body?.toByteArray() ?: ByteArray(0)
        }
    }

    /**
     * 객체의 상태 태그를 [TAG_STATUS_COMMITTED]로 바꿔 확정 처리한다.
     *
     * 확정된 객체는 `status=temp` 라이프사이클 룰의 대상에서 제외되므로 자동으로 삭제되지 않는다.
     * `PutObjectTagging`은 기존 태그 전체를 교체하므로, 이후 다른 태그가 추가되면
     * 여기서 함께 유지해야 한다.
     *
     * @param objectKey 확정할 S3 객체 키
     */
    private suspend fun markCommitted(objectKey: String) {
        s3Client.putObjectTagging(
            PutObjectTaggingRequest {
                bucket = bucketName
                key = objectKey
                tagging = Tagging {
                    tagSet = listOf(
                        Tag {
                            key = TAG_STATUS
                            value = TAG_STATUS_COMMITTED
                        },
                    )
                }
            },
        )
    }

    fun downloadObject(key: String): S3ObjectContent {
        val request = GetObjectRequest {
            bucket = bucketName
            this.key = key
        }

        return runBlocking {
            s3Client.getObject(request) { response ->
                S3ObjectContent(
                    bytes = response.body?.toByteArray()
                        ?: throw IllegalStateException("S3 객체 바디가 비어 있습니다: $key"),
                    contentType = response.contentType,
                )
            }
        }
    }
}

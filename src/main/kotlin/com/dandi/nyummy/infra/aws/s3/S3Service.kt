package com.dandi.nyummy.infra.aws.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.net.url.Url
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
        private const val TEMP_PREFIX = "temp"

        private val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/heic",
            "image/webp",
        )

        private val MIME_TO_EXTENSION = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/heic" to "heic",
            "image/webp" to "webp",
        )
    }

    private val tika = Tika()

    /**
     * 임시 경로(`temp/`)에 파일을 업로드할 수 있는 presigned URL을 발급한다.
     *
     * @param contentType 업로드할 파일의 MIME 타입. [ALLOWED_CONTENT_TYPES]에 포함된 값만 허용
     * @param fileSizeBytes 업로드할 파일의 크기(byte)
     * @param maxFileSizeBytes 허용되는 최대 파일 크기(byte)
     * @param expiration presigned URL의 유효 기간
     * @return 업로드용 presigned URL과 임시 객체 키를 담은 [S3UploadResult]
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] contentType이 허용 목록에 없을 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] fileSizeBytes가 음수이거나 maxFileSizeBytes를 초과할 경우
     */
    fun createUploadUrl(
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

        // TODO: 경로에 user_id 추가하기
        val tempExtension = MIME_TO_EXTENSION.getValue(contentType)
        val key =
            "$TEMP_PREFIX/${UUID.randomUUID()}.$tempExtension"
        val url = createPresignedPutUrl(key, contentType, expiration)

        return S3UploadResult(url, key)
    }

    /**
     * 임시 업로드된 객체를 검증한 뒤 최종 경로로 확정(복사)하고 임시 객체는 삭제한다.
     *
     * @param tempKey [createUploadUrl]로 발급받아 업로드에 사용한 임시 객체 키 (`temp/`로 시작해야 함)
     * @param finalKeyPrefix 확정된 객체가 저장될 경로의 접두사
     * @param maxFileSizeBytes 허용되는 최대 파일 크기(byte)
     * @return 확정된 최종 객체 키
     * @throws BusinessException [S3ErrorCode.INVALID_KEY] tempKey가 `temp/`로 시작하지 않을 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] tempKey에 해당하는 객체가 S3에 존재하지 않을 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 실제 업로드된 크기가 0이거나 maxFileSizeBytes를 초과할 경우 (이 경우 임시 객체는 삭제됨)
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] 실제 콘텐츠에서 감지된 MIME 타입이 허용 목록에 없을 경우 (이 경우 임시 객체는 삭제됨)
     */
    fun confirmUpload(tempKey: String, finalKeyPrefix: String, maxFileSizeBytes: Long): String = runBlocking {
        if (!tempKey.startsWith("$TEMP_PREFIX/")) {
            throw BusinessException(S3ErrorCode.INVALID_KEY)
        }

        val head = try {
            s3Client.headObject {
                bucket = bucketName
                key = tempKey
            }
        } catch (e: NotFound) {
            throw BusinessException(S3ErrorCode.OBJECT_NOT_FOUND)
        }

        val actualSize = head.contentLength ?: 0L

        if (actualSize == 0L || actualSize > maxFileSizeBytes) {
            deleteObject(tempKey)
            throw BusinessException(S3ErrorCode.FILE_SIZE_EXCEEDED)
        }

        val headerBytes = downloadObjectRange(tempKey, "bytes=0-1023")

        val detectedMimeType = tika.detect(headerBytes) // 메모리 측정해보기

        val safeExtension = MIME_TO_EXTENSION[detectedMimeType]
            ?: run {
                deleteObject(tempKey)
                throw BusinessException(S3ErrorCode.UNSUPPORTED_CONTENT_TYPE)
            }

        val today = LocalDate.now(clock)

        // TODO: 경로에 user_id 추가
        val finalKey =
            "$finalKeyPrefix/${today.year}/${today.monthValue}/${today.dayOfMonth}/${UUID.randomUUID()}.$safeExtension"

        s3Client.copyObject(
            CopyObjectRequest {
                bucket = bucketName
                copySource = "$bucketName/$tempKey"
                key = finalKey
            },
        )

        deleteObject(tempKey)
        finalKey
    }

    /**
     * 객체를 다운로드할 수 있는 presigned URL을 발급한다.
     *
     * @param key 조회할 S3 객체 키
     * @param duration presigned URL의 유효 기간
     * @return 다운로드용 presigned [Url]
     */
    fun createPresignedGetUrl(key: String, duration: Duration): Url {
        val request = GetObjectRequest {
            bucket = bucketName
            this.key = key
        }
        return runBlocking { s3Client.presignGetObject(request, duration) }.url
    }

    /**
     * 객체를 업로드할 수 있는 presigned URL을 발급한다.
     *
     * @param key 업로드될 S3 객체 키
     * @param contentType 업로드할 파일의 MIME 타입
     * @param duration presigned URL의 유효 기간
     * @return 업로드용 presigned [Url]
     */
    private fun createPresignedPutUrl(key: String, contentType: String, duration: Duration): Url {
        val request = PutObjectRequest {
            bucket = bucketName
            this.key = key
            this.contentType = contentType
        }
        return runBlocking { s3Client.presignPutObject(request, duration) }.url
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
     * 객체를 S3에서 삭제한다.
     *
     * @param key 삭제할 S3 객체 키
     */
    private suspend fun deleteObject(key: String) {
        s3Client.deleteObject(
            DeleteObjectRequest {
                bucket = bucketName
                this.key = key
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

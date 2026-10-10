package com.dandi.nyummy.infra.storage.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NoSuchKey
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
import com.dandi.nyummy.infra.storage.s3.dto.S3ObjectContent
import com.dandi.nyummy.infra.storage.s3.dto.S3UploadResult
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import kotlin.time.Duration

/**
 * S3 버킷 접근 어댑터.
 *
 * 버킷 이름, SDK 예외 번역, presigned URL 서명만 담당하며 도메인(식사·문의 등)을 알지 않는다.
 * 객체 키 규칙이나 업로드 정책은 호출하는 쪽이 정한다.
 *
 * 업로드 객체는 `status=temp` 태그로 시작해 [confirmObject]에서 `status=committed`로 바뀐다.
 * 버킷의 라이프사이클 룰이 `status=temp` 객체를 정리하므로, 확정되지 않은 업로드는 서버가 따로 삭제하지 않는다.
 */
@Component
class S3StorageClient(private val s3Client: S3Client, @Value("\${AWS_S3_BUCKET_NAME}") private val bucketName: String) {
    companion object {
        const val TAG_STATUS = "status"
        const val TAG_STATUS_TEMP = "temp"
        const val TAG_STATUS_COMMITTED = "committed"
        private val logger = LoggerFactory.getLogger(S3StorageClient::class.java)
    }

    /**
     * 객체를 업로드할 수 있는 presigned URL을 발급한다.
     *
     * `status=temp` 태그가 URL에 함께 서명된다. 따라서 클라이언트는 [S3UploadResult.uploadHeaders]를
     * 업로드 요청에 그대로 포함해야 하며, 하나라도 누락하면 서명이 일치하지 않아 업로드가 거부된다.
     *
     * @param objectKey 업로드될 S3 객체 키
     * @param contentType 업로드할 파일의 MIME 타입
     * @param expiration presigned URL의 유효 기간
     * @return 업로드용 presigned URL, 객체 키, 필수 요청 헤더를 담은 [S3UploadResult]
     */
    fun createPresignedPutUrl(objectKey: String, contentType: String, expiration: Duration): S3UploadResult {
        val objectTagging = "$TAG_STATUS=$TAG_STATUS_TEMP"

        val request = PutObjectRequest {
            bucket = bucketName
            key = objectKey
            this.contentType = contentType
            tagging = objectTagging
        }

        val url = runBlocking { s3Client.presignPutObject(request, expiration) }.url.toString()

        val uploadHeaders = mapOf(
            "Content-Type" to contentType,
            "x-amz-tagging" to objectTagging,
        )

        return S3UploadResult(url, objectKey, uploadHeaders)
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
     * 객체의 실제 크기(byte)를 조회한다.
     *
     * @param key 조회할 S3 객체 키
     * @return 객체 크기. S3가 크기를 돌려주지 않으면 0
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] 객체가 존재하지 않을 경우
     */
    suspend fun getObjectSize(key: String): Long {
        val head = try {
            s3Client.headObject {
                bucket = bucketName
                this.key = key
            }
        } catch (e: NotFound) {
            throw BusinessException(S3ErrorCode.OBJECT_NOT_FOUND)
        }

        return head.contentLength ?: 0L
    }

    /**
     * 객체의 상태 태그를 [TAG_STATUS_COMMITTED]로 바꿔 확정 처리한다.
     *
     * 확정된 객체는 `status=temp` 라이프사이클 룰의 대상에서 제외되므로 자동으로 삭제되지 않는다.
     * `PutObjectTagging`은 기존 태그 전체를 교체하므로, 이후 다른 태그가 추가되면
     * 여기서 함께 유지해야 한다.
     *
     * @param key 확정할 S3 객체 키
     */
    suspend fun confirmObject(key: String) {
        s3Client.putObjectTagging(
            PutObjectTaggingRequest {
                bucket = bucketName
                this.key = key
                tagging = Tagging {
                    tagSet = listOf(
                        Tag {
                            this.key = TAG_STATUS
                            value = TAG_STATUS_COMMITTED
                        },
                    )
                }
            },
        )
    }

    fun downloadText(key: String): String = runBlocking {
        val content = try {
            downloadObject(key)
        } catch (e: NoSuchKey) {
            logger.error("S3 객체를 찾을 수 없습니다: key={}", key, e)
            throw BusinessException(S3ErrorCode.OBJECT_READ_FAILED)
        }

        content.bytes.toString(Charsets.UTF_8)
    }

    suspend fun downloadObject(key: String): S3ObjectContent {
        val request = GetObjectRequest {
            bucket = bucketName
            this.key = key
        }

        return s3Client.getObject(request) { response ->
            S3ObjectContent(
                bytes = response.body?.toByteArray()
                    ?: throw IllegalStateException("S3 객체 바디가 비어 있습니다: $key"),
                contentType = response.contentType,
            )
        }
    }
}

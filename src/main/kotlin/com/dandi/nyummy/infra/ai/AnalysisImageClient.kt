package com.dandi.nyummy.infra.ai

import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import com.dandi.nyummy.infra.storage.s3.dto.S3ObjectContentResult
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * 분석할 식사 이미지를 S3에서 받는다.
 *
 * S3의 실패를 [AnalysisFailureCategory]로 바꿔 던진다. 받는 쪽(Worker)이 이미지를 받지 못한 것도 Gemini의 실패와
 * 같은 규칙으로 재시도할지, 미룰지, 실패로 끝낼지 정하게 하려는 것이다.
 */
@Component
class AnalysisImageClient(private val s3StorageClient: S3StorageClient) {

    /**
     * 식사 이미지를 받는다.
     *
     * 객체가 없거나 비었으면 다시 해도 소용없으므로 [AnalysisFailureCategory.INVALID_REQUEST],
     * 권한 오류(403)는 이미지와 상관없이 모든 요청이 거부되므로 [AnalysisFailureCategory.MISCONFIGURED],
     * S3 서버 오류·요청 한도·네트워크 오류는 [AnalysisFailureCategory.UNAVAILABLE]이다.
     *
     * @param imageKey 받을 이미지의 S3 키
     * @return 이미지 바이트와 S3에 저장된 콘텐츠 타입
     * @throws AnalysisFailureException 이미지를 받지 못한 경우
     */
    fun downloadImage(imageKey: String): S3ObjectContentResult = try {
        runBlocking { s3StorageClient.downloadObject(imageKey) }
    } catch (e: NoSuchKey) {
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_REQUEST, "분석할 이미지가 없습니다: $imageKey", e)
    } catch (e: ServiceException) {
        val metadata = e.sdkErrorMetadata
        val category = when {
            (metadata.protocolResponse as? HttpResponse)?.status?.value == HttpStatus.FORBIDDEN.value() ->
                AnalysisFailureCategory.MISCONFIGURED

            metadata.errorType == ServiceException.ErrorType.Client && !metadata.isThrottling ->
                AnalysisFailureCategory.INVALID_REQUEST

            else -> AnalysisFailureCategory.UNAVAILABLE
        }
        throw AnalysisFailureException(category, "분석할 이미지를 받지 못했습니다: $imageKey", e)
    } catch (e: SdkBaseException) {
        // 응답을 받기 전의 실패(타임아웃·연결 오류·재시도 소진)다.
        throw AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "분석할 이미지를 받지 못했습니다: $imageKey", e)
    } catch (e: IllegalStateException) {
        // S3StorageClient가 바디 없는 객체를 이렇게 알린다.
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_REQUEST, "분석할 이미지가 비어 있습니다: $imageKey", e)
    }
}

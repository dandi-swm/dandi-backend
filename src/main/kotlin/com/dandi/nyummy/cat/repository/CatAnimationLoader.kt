package com.dandi.nyummy.cat.repository

import com.dandi.nyummy.cat.dto.CatAnimationResponse
import com.dandi.nyummy.cat.enum.CatWeight
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CatErrorCode
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * 체형별 애니메이션 메타데이터를 S3에서 읽어온다.
 *
 * 체형은 5종뿐이고 내용도 배포된 에셋에 고정되므로 [CatWeight]를 키로 캐시한다.
 * 호출자(userId)별로 캐시하면 엔트리가 사용자 수만큼 늘어나고, 체형이 바뀌어도
 * 캐시가 만료될 때까지 이전 체형의 응답이 나가므로 키를 체형으로 좁히는 것이 중요하다.
 *
 * [CatService]와 별도 빈으로 둔 이유도 캐시 때문이다. 같은 빈 안에서 호출하면
 * AOP 프록시를 거치지 않아 [Cacheable]이 예외 없이 무시된다.
 */
@Component
class CatAnimationLoader(private val s3StorageClient: S3StorageClient, private val objectMapper: ObjectMapper) {
    companion object {
        private const val CAT_PREFIX = "cats"
        private const val METADATA_FILE = "metadata.json"
        private val logger = LoggerFactory.getLogger(CatAnimationLoader::class.java)
    }

    /**
     * 체형에 해당하는 `cats/{체형}/metadata.json`을 읽어 역직렬화한다.
     *
     * 반환되는 객체는 캐시에 보관되어 모든 요청이 공유하므로 호출부에서 변경해서는 안 된다.
     *
     * @param weight 조회할 체형
     * @return 해당 체형의 애니메이션 메타데이터
     * @throws BusinessException [CatErrorCode.ANIMATION_METADATA_INVALID] 메타데이터가 기대한 형식이 아닐 경우
     */
    @Cacheable(cacheNames = ["catAnimations"])
    fun load(weight: CatWeight): CatAnimationResponse {
        // 체형은 경로에만 담기고 파일명은 모든 체형이 같으므로, slug가 어긋나면 엉뚱한 체형을 읽는다.
        val key = "$CAT_PREFIX/${weight.slug}/$METADATA_FILE"
        val json = s3StorageClient.downloadText(key)

        // 캐시가 비어 있을 때만 호출되므로, 이 로그가 매 요청 찍히면 캐시가 동작하지 않는 것이다.
        logger.info("애니메이션 메타데이터 적재: key={}", key)

        return try {
            objectMapper.readValue<CatAnimationResponse>(json)
        } catch (e: JacksonException) {
            logger.error("애니메이션 메타데이터 역직렬화 실패: key={}", key, e)
            throw BusinessException(CatErrorCode.ANIMATION_METADATA_INVALID)
        }
    }
}

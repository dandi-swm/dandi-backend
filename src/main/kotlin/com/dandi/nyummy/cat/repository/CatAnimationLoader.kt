package com.dandi.nyummy.cat.repository

import com.dandi.nyummy.cat.dto.CatAnimationResponse
import com.dandi.nyummy.cat.enum.CatWeight
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CatErrorCode
import com.dandi.nyummy.infra.aws.s3.S3Service
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

@Component
class CatAnimationLoader(private val s3Service: S3Service, private val objectMapper: ObjectMapper) {
    companion object {
        private const val CAT_PREFIX = "cats"
        private const val METADATA_FILE = "metadata.json"
        private val logger = LoggerFactory.getLogger(CatAnimationLoader::class.java)
    }

    @Cacheable(cacheNames = ["catAnimations"])
    fun load(weight: CatWeight): CatAnimationResponse {
        val key = "$CAT_PREFIX/${weight.slug}/$METADATA_FILE"
        val json = s3Service.downloadText(key)

        return try {
            logger.info("CatAnimationLoader.load() 실행")
            objectMapper.readValue<CatAnimationResponse>(json)
        } catch (e: JacksonException) {
            logger.error("애니메이션 메타데이터 역직렬화 실패: key={}", key, e)
            throw BusinessException(CatErrorCode.ANIMATION_METADATA_INVALID)
        }
    }
}

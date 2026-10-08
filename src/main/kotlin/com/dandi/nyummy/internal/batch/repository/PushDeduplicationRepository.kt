package com.dandi.nyummy.internal.batch.repository

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class PushDeduplicationRepository(private val redisTemplate: StringRedisTemplate) {
    companion object {
        private const val KEY_PREFIX = "push-sent:"
        private val logger = LoggerFactory.getLogger(PushDeduplicationRepository::class.java)
    }

    /**
     * 같은 알림을 중복 발송하지 않도록 표시한다. 처음 표시한 호출만 true를 받는다.
     *
     * SETNX(SET if Not eXists)라 배치가 동시에 두 번 돌아도 한쪽만 통과한다. 젠킨스 재시도나 잡 중복 실행에서
     * 사용자가 알림을 두 번 받는 것을 막는다.
     *
     * Redis 접근이 실패하면 true를 돌려준다(fail-open). 알림을 아예 못 보내는 것보다
     * 드물게 중복되는 것이 낫다.
     */
    fun markSent(key: String, timeToLive: Duration): Boolean = try {
        redisTemplate.opsForValue().setIfAbsent("$KEY_PREFIX$key", "1", timeToLive) == true
    } catch (e: DataAccessException) {
        logger.error("푸시 중복 발송 방지 실패, 발송은 그대로 진행: key={}", key, e)
        true
    }
}

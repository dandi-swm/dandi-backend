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

    /**
     * 발송 표시를 지워 다음 재시도가 다시 보낼 수 있게 한다.
     *
     * 일시 장애로 못 받은 사용자에게 쓴다. 표시가 남아 있으면 젠킨스가 재시도해도 [markSent]에
     * 막혀 그 사용자를 건너뛰므로, 표시를 지우지 않는 한 재시도는 복구 수단이 되지 못한다.
     *
     * 지운 뒤 재시도하면 드물게 중복이 생길 수 있다. 일시 장애로 분류됐지만 실제로는 FCM이
     * 접수한 경우다. 그래도 지우는 쪽을 고른 이유는, 조용히 영구 누락되는 것보다 드문 중복이
     * 낫다는 [markSent]의 fail-open과 같은 판단이다.
     *
     * 실패하면 로그만 남긴다. 발송은 이미 끝났으므로 예외를 올려 배치를 실패시킬 이유가 없고,
     * 표시가 남은 결과는 이 메서드가 없던 때와 같다.
     */
    fun deleteSent(keys: Collection<String>) {
        if (keys.isEmpty()) {
            return
        }

        try {
            redisTemplate.delete(keys.map { "$KEY_PREFIX$it" })
        } catch (e: DataAccessException) {
            logger.error("푸시 발송 표시 삭제 실패: count={}", keys.size, e)
        }
    }
}

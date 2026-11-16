package com.dandi.nyummy.internal.batch.repository

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.SetCondition
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.types.Expiration
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class PushDeduplicationRepository(private val redisTemplate: StringRedisTemplate) {
    companion object {
        private const val KEY_PREFIX = "push-sent:"
        private const val SENT_MARK_VALUE = "1"
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
    fun createSentMark(key: String, timeToLive: Duration): Boolean = try {
        redisTemplate.opsForValue().setIfAbsent("$KEY_PREFIX$key", SENT_MARK_VALUE, timeToLive) == true
    } catch (e: DataAccessException) {
        logger.error("푸시 중복 발송 방지 실패, 발송은 그대로 진행: key={}", key, e)
        true
    }

    /**
     * 여러 알림의 발송 표시를 파이프라인으로 한 번에 만든다. 처음 표시한 키만 돌려준다.
     *
     * 키마다 [createSentMark]와 같은 SET NX를 보내되, 응답을 기다리지 않고 연달아 보낸 뒤 결과를 모아 받는다.
     * 키마다 하나씩 보내고 기다리면 키 수만큼 Redis를 왕복한다(식사 리마인더 10만 명에 약 5만 번).
     *
     * 키마다 따로 판정하므로 이미 표시된 키만 빠진다. 파이프라인은 여러 명령을 한 덩어리로 실행하지는 않지만
     * 명령 하나하나(SET NX)는 원자적이라, 배치가 동시에 두 번 돌아도 키마다 한쪽만 통과한다.
     *
     * Redis 접근이 실패하면 모든 키를 돌려준다(fail-open). 어느 키에서 실패했는지 가리지 못해서,
     * [createSentMark]처럼 못 보내는 것보다 드문 중복을 고른다.
     */
    fun createSentMarks(keys: List<String>, timeToLive: Duration): Set<String> {
        if (keys.isEmpty()) {
            return emptySet()
        }

        return try {
            val expiration = Expiration.from(timeToLive)
            val ifAbsent = SetCondition.ifAbsent()
            // 콜백이 받는 연결은 바이트 명령만 받는다. 키와 값은 템플릿과 같은 직렬화(UTF-8 문자열)로 바꿔 보낸다
            val serializer = redisTemplate.stringSerializer
            val value = serializer.serialize(SENT_MARK_VALUE)

            // 콜백 안에서 부른 명령은 바로 보내지 않고 모았다가 한꺼번에 보낸다. 결과는 보낸 순서대로 돌아온다
            val results = redisTemplate.executePipelined(
                RedisCallback<Any?> { connection ->
                    keys.forEach { key ->
                        connection.stringCommands().set(
                            serializer.serialize("$KEY_PREFIX$key"),
                            value,
                            ifAbsent,
                            expiration,
                        )
                    }
                    // 파이프라인 안에서는 명령의 결과를 바로 받을 수 없어 null을 돌려준다
                    null
                },
            )

            keys.filterIndexed { index, _ -> results[index] == true }.toSet()
        } catch (e: DataAccessException) {
            logger.error("푸시 중복 발송 방지 실패, 발송은 그대로 진행: count={}", keys.size, e)
            keys.toSet()
        }
    }

    /**
     * 발송 표시를 지워 다음 재시도가 다시 보낼 수 있게 한다.
     *
     * 일시 장애로 못 받은 사용자에게 쓴다. 표시가 남아 있으면 젠킨스가 재시도해도 [createSentMark]에
     * 막혀 그 사용자를 건너뛰므로, 표시를 지우지 않는 한 재시도는 복구 수단이 되지 못한다.
     *
     * 지운 뒤 재시도하면 드물게 중복이 생길 수 있다. 일시 장애로 분류됐지만 실제로는 FCM이
     * 접수한 경우다. 그래도 지우는 쪽을 고른 이유는, 조용히 영구 누락되는 것보다 드문 중복이
     * 낫다는 [createSentMark]의 fail-open과 같은 판단이다.
     *
     * 실패하면 로그만 남긴다. 발송은 이미 끝났으므로 예외를 올려 배치를 실패시킬 이유가 없고,
     * 표시가 남은 결과는 이 메서드가 없던 때와 같다.
     */
    fun deleteSentMarks(keys: Collection<String>) {
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

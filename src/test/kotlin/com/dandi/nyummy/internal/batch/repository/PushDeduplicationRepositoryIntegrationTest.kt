package com.dandi.nyummy.internal.batch.repository

import io.mockk.every
import io.mockk.mockk
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 발송 표시 파이프라인을 실제 Valkey에서 돌린다. 파이프라인 결과의 순서·타입과 TTL은 mock으로 확인할 수 없다.
 *
 * Spring 컨텍스트 없이 연결을 직접 만든다. 통합 테스트 컨텍스트를 하나 더 캐시하지 않으려는 것이다.
 */
@Testcontainers
class PushDeduplicationRepositoryIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val valkey = GenericContainer<Nothing>(DockerImageName.parse("valkey/valkey:9.1-alpine")).apply {
            withExposedPorts(6379)
        }

        private val timeToLive = Duration.ofHours(25)
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var pushDeduplicationRepository: PushDeduplicationRepository

    @BeforeTest
    fun setUp() {
        connectionFactory =
            LettuceConnectionFactory(RedisStandaloneConfiguration(valkey.host, valkey.getMappedPort(6379)))
        connectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(connectionFactory)
        pushDeduplicationRepository = PushDeduplicationRepository(redisTemplate)
        connectionFactory.connection.use { it.serverCommands().flushAll() }
    }

    @AfterTest
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `처음 표시하는 키만 돌려주고 이미 표시된 키는 뺀다`() {
        pushDeduplicationRepository.createSentMark("already-sent", timeToLive)

        val markedKeys = pushDeduplicationRepository.createSentMarks(
            listOf("first", "already-sent", "second"),
            timeToLive,
        )

        assertEquals(setOf("first", "second"), markedKeys)
        assertEquals("1", redisTemplate.opsForValue().get("push-sent:first"))
        assertEquals("1", redisTemplate.opsForValue().get("push-sent:second"))
    }

    @Test
    fun `같은 키를 다시 표시하면 아무것도 돌려주지 않는다`() {
        pushDeduplicationRepository.createSentMarks(listOf("first", "second"), timeToLive)

        val markedKeys = pushDeduplicationRepository.createSentMarks(listOf("first", "second"), timeToLive)

        assertEquals(emptySet(), markedKeys)
    }

    @Test
    fun `발송 표시에 TTL을 건다`() {
        pushDeduplicationRepository.createSentMarks(listOf("first"), timeToLive)

        val expireSeconds = redisTemplate.getExpire("push-sent:first")
        assertTrue(expireSeconds in 1..timeToLive.seconds, "TTL: $expireSeconds")
    }

    @Test
    fun `빈 목록이면 빈 결과를 돌려준다`() {
        assertEquals(emptySet(), pushDeduplicationRepository.createSentMarks(emptyList(), timeToLive))
    }

    @Test
    fun `Redis 접근이 실패하면 모든 키를 돌려준다`() {
        val failingRedisTemplate = mockk<StringRedisTemplate> {
            every { stringSerializer } returns StringRedisSerializer.UTF_8
            every { executePipelined(any<RedisCallback<*>>()) } throws RedisConnectionFailureException("Redis 장애")
        }

        val markedKeys = PushDeduplicationRepository(failingRedisTemplate)
            .createSentMarks(listOf("first", "second"), timeToLive)

        assertEquals(setOf("first", "second"), markedKeys)
    }
}

package com.dandi.nyummy.auth.repository

import com.dandi.nyummy.security.jwt.JwtProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 사용자별 토큰 무효화 기준 시각을 Redis에 보관한다.
 *
 * 무효화한 토큰을 하나씩 등재하는 블랙리스트가 아니라, 사용자당 시각 하나만 저장한다.
 * 저장량이 무효화 횟수가 아니라 사용자 수에만 비례하고, 무효화 시점에 대상 토큰을 몰라도 되므로
 * 로그아웃·비밀번호 변경·탈취 대응을 같은 방식으로 처리할 수 있다.
 *
 * 무효화 단위가 사용자 전체라 특정 세션만 끊을 수는 없다.
 * RefreshToken을 사용자당 1:1로 관리해 세션이 하나뿐인 현재 정책을 전제로 한다.
 */
@Repository
class TokenInvalidationRepository(
    private val redisTemplate: StringRedisTemplate,
    private val jwtProperties: JwtProperties,
) {
    companion object {
        private const val KEY_PREFIX = "token-invalidated-at:user:"
    }

    /**
     * 해당 시각 이전에 발급된 AccessToken을 모두 무효화한다.
     *
     * TTL은 AccessToken 수명과 같다. 이 키가 막아야 할 토큰 중 가장 늦게 만료되는 것은
     * invalidatedAt 직전에 발급된 토큰인데, 그마저도 invalidatedAt + accessTimeToLive 이전에 만료되므로
     * 키가 그보다 오래 남을 이유가 없다.
     */
    fun createInvalidatedAt(userId: Long, invalidatedAt: Instant) {
        redisTemplate.opsForValue().set(
            key(userId),
            invalidatedAt.epochSecond.toString(),
            jwtProperties.accessTimeToLive,
        )
    }

    /**
     * 무효화 기준 시각을 조회한다. 무효화 이력이 없거나 TTL이 지나 키가 사라졌으면 null이다.
     */
    fun getInvalidatedAt(userId: Long): Instant? = redisTemplate.opsForValue()
        .get(key(userId))
        ?.toLongOrNull()
        ?.let(Instant::ofEpochSecond)

    private fun key(userId: Long) = "$KEY_PREFIX$userId"
}

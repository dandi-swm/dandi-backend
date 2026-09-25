package com.dandi.nyummy.auth.service

import com.dandi.nyummy.auth.entity.RefreshToken
import com.dandi.nyummy.auth.repository.RefreshTokenRepository
import com.dandi.nyummy.security.jwt.JwtProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtProperties: JwtProperties,
    private val clock: Clock,
) {

    /**
     * 새 세션을 시작하며 RefreshToken을 저장한다. 로그인·회원가입 등 세션이 시작되는 모든 경로가 공유한다.
     *
     * 세션의 시작이므로 절대 만료(absoluteExpiresAt)를 app.jwt.refresh-absolute-time-to-live 만큼 뒤로 새로 찍는다.
     * user_id가 UNIQUE라 사용자당 행이 하나뿐이므로, 기존 행이 있으면 지우지 않고 갱신(restart)하고 없으면 새로 저장한다.
     *
     * 별도 빈인 이유: 호출부([AuthService])가 트랜잭션 밖에서 외부 검증을 마친 뒤 DB 쓰기만 트랜잭션으로 묶을 수 있게
     * 하기 위해서다. 같은 클래스 안의 자기 호출은 프록시를 거치지 않아 @Transactional이 적용되지 않는다.
     * 이미 트랜잭션 안에서 호출되면(회원가입) 그 트랜잭션에 참여한다.
     *
     * @param userId 세션을 시작하는 사용자 ID
     * @param refreshToken 새로 발급된 RefreshToken
     */
    @Transactional
    fun createOrRestart(userId: Long, refreshToken: String) {
        val absoluteExpiresAt = Instant.now(clock).plus(jwtProperties.refreshAbsoluteTimeToLive)

        val existingToken = refreshTokenRepository.findByUserId(userId)

        if (existingToken != null) {
            existingToken.restart(refreshToken, absoluteExpiresAt)
            return
        }

        refreshTokenRepository.save(
            RefreshToken(
                refreshToken = refreshToken,
                absoluteExpiresAt = absoluteExpiresAt,
                userId = userId,
            ),
        )
    }
}

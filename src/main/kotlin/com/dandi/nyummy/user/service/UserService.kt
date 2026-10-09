package com.dandi.nyummy.user.service

import com.dandi.nyummy.auth.repository.TokenInvalidationRepository
import com.dandi.nyummy.auth.service.RefreshTokenService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.security.jwt.TokenService
import com.dandi.nyummy.user.dto.PasswordUpdateRequest
import com.dandi.nyummy.user.dto.PasswordUpdateResponse
import com.dandi.nyummy.user.dto.PushSettingResponse
import com.dandi.nyummy.user.dto.UpdateMealTimeRequest
import com.dandi.nyummy.user.dto.UpdatePushSettingRequest
import com.dandi.nyummy.user.dto.UserResponse
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.mapper.toPushSettingResponse
import com.dandi.nyummy.user.mapper.toUserResponse
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class UserService(
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val passwordService: PasswordService,
    private val tokenService: TokenService,
    private val refreshTokenService: RefreshTokenService,
    private val tokenInvalidationRepository: TokenInvalidationRepository,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(UserService::class.java)
    }

    @Transactional(readOnly = true)
    fun getMe(id: Long): UserResponse {
        val user = userRepository.findByIdOrNull(id)
            ?: throw BusinessException(UserErrorCode.USER_NOT_FOUND)

        val profile = profileRepository.getProfileByUserId(user.id)
            ?: throw BusinessException(UserErrorCode.USER_NOT_FOUND)

        return user.toUserResponse(profile)
    }

    /**
     * 알림 수신 설정을 조회한다. 설정 화면의 토글 상태를 그리는 데 필요한 값만 돌려준다.
     *
     * @throws BusinessException [UserErrorCode.PROFILE_NOT_FOUND] 프로필이 없는 경우
     */
    @Transactional(readOnly = true)
    fun getPushSetting(userId: Long): PushSettingResponse {
        val profile = getProfile(userId)

        return profile.toPushSettingResponse()
    }

    /**
     * 알림 수신 설정을 바꾼다. 요청에 담기지 않은 항목(null)은 건드리지 않는다.
     *
     * @throws BusinessException [UserErrorCode.PROFILE_NOT_FOUND] 프로필이 없는 경우
     */
    @Transactional
    fun updatePushSetting(userId: Long, request: UpdatePushSettingRequest) {
        val profile = getProfile(userId)

        request.isServicePushEnabled?.let { profile.updateServicePushEnabled(it) }
        request.isMarketingPushEnabled?.let { profile.updateMarketingPushEnabled(it, Instant.now(clock)) }
    }

    @Transactional
    fun updateMealTime(userId: Long, request: UpdateMealTimeRequest) {
        val profile = getProfile(userId)
        profile.updateMealTime(request.breakfastHour, request.lunchHour, request.dinnerHour)
    }

    /**
     * 프로필은 가입 시 함께 생성되므로 없으면 데이터 정합성 문제다. 로그를 남기고 끊는다.
     *
     * @throws BusinessException [UserErrorCode.PROFILE_NOT_FOUND] 프로필이 없는 경우
     */
    private fun getProfile(userId: Long): Profile = profileRepository.getProfileByUserId(userId)
        ?: run {
            logger.error("가입 시 생성되어야 할 프로필이 없습니다: userId={}", userId)
            throw BusinessException(UserErrorCode.PROFILE_NOT_FOUND)
        }

    /**
     * 현재 비밀번호를 확인해 새 비밀번호로 교체하고, 토큰 무효화 기준을 기록한 뒤 새 토큰 쌍을 발급한다.
     *
     * 변경을 요청한 현재 기기가 새 토큰으로 인증을 이어갈 수 있도록 RefreshToken 행을 지우지 않고
     * 새 값으로 교체(restart)하며, 새 AccessToken·RefreshToken을 응답에 담아 클라이언트가 즉시 바꿔 끼우게 한다.
     * 옛 RefreshToken은 저장된 값과 달라져 재발급에 실패하고, AccessToken은 무효화 기준 시각 이전에 발급된 경우 거부된다.
     * main의 초 단위 비교 정책을 사용하므로 무효화와 같은 초에 발급된 AccessToken은 차단되지 않을 수 있다.
     *
     * 순서가 중요하다.
     * 1. 비밀번호 교체([PasswordService] 트랜잭션에 참여) — 현재 비밀번호가 틀리면 여기서 끝나므로 토큰은 건드리지 않는다.
     * 2. 무효화 기준 시각 기록 — 새 토큰 발급보다 먼저 해야 한다. 무효화 판정은 초 단위(iat < invalidatedAt)라
     *    발급을 먼저 하면 초 경계를 넘는 순간 방금 발급한 AccessToken이 거부된다.
     * 3. 새 토큰 쌍 발급.
     * 4. RefreshToken 행 갱신 — 삭제 후 저장이 아니라 restart여야 한다. user_id가 UNIQUE라 같은 트랜잭션에서
     *    delete + save를 하면 Hibernate가 INSERT를 DELETE보다 먼저 실행해 유니크 제약에 걸린다.
     *
     * 비밀번호 교체와 RefreshToken 갱신을 한 트랜잭션으로 묶어 둘 중 하나만 반영되는 상태를 막는다.
     * Redis 무효화 기록 실패는 로그아웃과 같은 정책으로 로그만 남기고 진행한다 — 예외를 올리면 비밀번호 교체까지
     * 롤백되는데, 옛 AccessToken은 어차피 남은 수명(최대 30분) 안에 만료되기 때문이다.
     *
     * @param userId 비밀번호를 변경할 사용자 ID
     * @param request 비밀번호 변경 요청 정보를 담은 [PasswordUpdateRequest] (현재 비밀번호, 새 비밀번호)
     * @return 새로 발급된 AccessToken·RefreshToken을 담은 [PasswordUpdateResponse]
     * @throws BusinessException [UserErrorCode.USER_NOT_FOUND] 사용자가 없는 경우
     * @throws BusinessException [UserErrorCode.PASSWORD_NOT_SET] 소셜 로그인 계정이라 비밀번호가 없는 경우
     * @throws BusinessException [UserErrorCode.PASSWORD_MISMATCH] 현재 비밀번호가 일치하지 않는 경우
     */
    @Transactional
    fun updatePassword(userId: Long, request: PasswordUpdateRequest): PasswordUpdateResponse {
        passwordService.updatePassword(userId, request.currentPassword, request.newPassword)

        try {
            tokenInvalidationRepository.createInvalidatedAt(userId, Instant.now(clock))
        } catch (e: DataAccessException) {
            logger.error("토큰 무효화 기록 실패: userId={}", userId, e)
        }

        val (accessToken, refreshToken) = tokenService.createTokenPair(userId)

        refreshTokenService.createOrRestart(userId, refreshToken)

        return PasswordUpdateResponse(accessToken, refreshToken)
    }
}

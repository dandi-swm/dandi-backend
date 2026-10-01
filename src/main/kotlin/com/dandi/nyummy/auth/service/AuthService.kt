package com.dandi.nyummy.auth.service

import com.dandi.nyummy.auth.config.AuthProperties
import com.dandi.nyummy.auth.dto.ConfirmAuthCodeRequest
import com.dandi.nyummy.auth.dto.ConfirmAuthCodeResponse
import com.dandi.nyummy.auth.dto.LoginRequest
import com.dandi.nyummy.auth.dto.LoginResponse
import com.dandi.nyummy.auth.dto.PasswordResetRequest
import com.dandi.nyummy.auth.dto.RefreshRequest
import com.dandi.nyummy.auth.dto.RefreshResponse
import com.dandi.nyummy.auth.dto.SendAuthCodeRequest
import com.dandi.nyummy.auth.dto.SendAuthCodeResponse
import com.dandi.nyummy.auth.dto.SignUpRequest
import com.dandi.nyummy.auth.dto.SignUpResponse
import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.auth.repository.RefreshTokenRepository
import com.dandi.nyummy.auth.repository.TokenInvalidationRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.infra.aws.ses.SesService
import com.dandi.nyummy.security.jwt.JwtProperties
import com.dandi.nyummy.security.jwt.TokenService
import com.dandi.nyummy.security.jwt.TokenType
import com.dandi.nyummy.security.jwt.VerifiedClaims
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.repository.UserRepository
import com.dandi.nyummy.user.service.PasswordService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val refreshTokenService: RefreshTokenService,
    private val tokenService: TokenService,
    private val codeService: CodeService,
    private val sesService: SesService,
    private val passwordService: PasswordService,
    private val authProperties: AuthProperties,
    private val jwtProperties: JwtProperties,
    private val clock: Clock,
    private val tokenInvalidationRepository: TokenInvalidationRepository,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AuthService::class.java)
    }

    /**
     * 이메일과 비밀번호로 사용자를 인증하고 AccessToken·RefreshToken을 발급한다.
     *
     * 조회는 EMAIL 계정으로 한정한다 — 같은 이메일의 소셜 계정은 다른 사용자이며 비밀번호 로그인 대상이 아니다.
     *
     * 로그인은 새 세션의 시작이므로 RefreshToken 저장은 [RefreshTokenService.createOrRestart]에 맡긴다.
     *
     * @param request 로그인 요청 정보를 담은 [LoginRequest] (이메일, 비밀번호)
     * @return 리다이렉트 URL과 AccessToken·RefreshToken을 담은 [LoginResponse]
     * @throws BusinessException [AuthErrorCode.INVALID_CREDENTIALS] 이메일에 해당하는 사용자가 없거나 비밀번호가 일치하지 않는 경우
     */
    @Transactional
    fun login(request: LoginRequest): LoginResponse {
        val user = userRepository.findByProviderAndEmail(AuthProvider.EMAIL, request.email)
            ?: throw BusinessException(AuthErrorCode.INVALID_CREDENTIALS)

        val encodedPassword = user.password
            ?: throw BusinessException(AuthErrorCode.INVALID_CREDENTIALS)

        if (!passwordService.matchesPassword(request.password, encodedPassword)) {
            throw BusinessException(AuthErrorCode.INVALID_CREDENTIALS)
        }

        val userId = user.id

        val (newAccessToken, newRefreshToken) = tokenService.createTokenPair(userId)

        refreshTokenService.createOrRestart(userId, newRefreshToken)

        val redirectUrl = authProperties.loginRedirectUrl

        return LoginResponse(redirectUrl, newAccessToken, newRefreshToken)
    }

    /**
     * 회원가입 용도의 verifiedToken을 검증하고 사용자·프로필을 생성한 뒤 AccessToken·RefreshToken을 발급한다.
     *
     * 이메일 인증([confirmAuthCode])과 소셜 로그인([OAuthService.login])이 발급한 verifiedToken을 같은 경로로 받는다.
     * 토큰의 provider에 따라 달라지는 것은 둘뿐이다 — 신원 키의 중복 검사와 비밀번호 필수 여부.
     * 비밀번호는 EMAIL 계정의 속성이므로 소셜 가입 요청에 비밀번호가 실려 와도 무시한다.
     *
     * 중복은 사전 조회로 검사하고, 동시 가입 경합은 DB 유니크 제약 위반을 같은 에러로 매핑해 방어한다.
     * 가입이 끝난 뒤 같은 토큰을 다시 쓰면 이 중복 검사에 걸리므로, 토큰의 일회성은 별도 저장 없이 보장된다.
     *
     * @param request 회원가입 요청 정보를 담은 [SignUpRequest] (verifiedToken, 비밀번호(이메일 가입만), 닉네임, 신체 정보)
     * @return 발급된 AccessToken·RefreshToken을 담은 [SignUpResponse]
     * @throws BusinessException [AuthErrorCode.VERIFICATION_EXPIRED] verifiedToken이 만료된 경우
     * @throws BusinessException [AuthErrorCode.UNAUTHORIZED] 토큰의 서명·형식·타입·클레임이 유효하지 않거나 회원가입 용도가 아닌 경우
     * @throws BusinessException [AuthErrorCode.PASSWORD_REQUIRED] 이메일 가입인데 비밀번호가 없는 경우
     * @throws BusinessException [AuthErrorCode.EMAIL_ALREADY_EXISTS] 이미 가입된 이메일인 경우
     * @throws BusinessException [AuthErrorCode.OAUTH_ACCOUNT_ALREADY_EXISTS] 이미 가입된 소셜 계정인 경우
     */
    @Transactional
    fun signup(request: SignUpRequest): SignUpResponse {
        val claims = tokenService.getVerifiedClaims(request.verifiedToken)

        if (claims.purpose != AuthPurpose.SIGNUP) {
            throw BusinessException(AuthErrorCode.UNAUTHORIZED)
        }

        val alreadyExistsError = if (claims.provider.isSocial) {
            AuthErrorCode.OAUTH_ACCOUNT_ALREADY_EXISTS
        } else {
            AuthErrorCode.EMAIL_ALREADY_EXISTS
        }

        if (existsUser(claims)) {
            throw BusinessException(alreadyExistsError)
        }

        val encodedPassword = if (claims.provider.isSocial) {
            null
        } else {
            val password = request.password
                ?: throw BusinessException(AuthErrorCode.PASSWORD_REQUIRED)

            passwordService.encodePassword(password)
        }

        val savedUser = try {
            userRepository.save(
                User(
                    provider = claims.provider,
                    providerUserId = claims.providerUserId,
                    email = claims.email,
                    password = encodedPassword,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw BusinessException(alreadyExistsError)
        }

        val userId = savedUser.id

        profileRepository.save(
            Profile(
                nickname = request.nickname,
                birth = request.birth,
                gender = request.gender,
                height = request.height,
                weight = request.weight,
                userId = userId,
            ),
        )

        val (accessToken, refreshToken) = tokenService.createTokenPair(userId)

        refreshTokenService.createOrRestart(userId, refreshToken)

        return SignUpResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
        )
    }

    /**
     * provider별 신원 키로 가입 여부를 본다 — EMAIL은 이메일, 소셜은 providerUserId.
     * 신원 키 클레임이 없는 토큰은 서버가 발급한 모양이 아니므로 거부한다.
     */
    private fun existsUser(claims: VerifiedClaims): Boolean = if (claims.provider.isSocial) {
        userRepository.existsByProviderAndProviderUserId(
            claims.provider,
            claims.providerUserId ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED),
        )
    } else {
        userRepository.existsByProviderAndEmail(
            claims.provider,
            claims.email ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED),
        )
    }

    /**
     * 리프레시 토큰을 검증하고 새 AccessToken·RefreshToken을 발급한다(rotate).
     *
     * 재발급은 토큰만 교체할 뿐 절대 만료(absoluteExpiresAt)를 연장하지 않는다.
     * 따라서 재발급을 아무리 반복해도 로그인 시점으로부터 app.jwt.refresh-absolute-time-to-live가 지나면
     * 이 API가 막히고 다시 로그인해야 한다.
     *
     * 절대 만료 검사는 이 API에서만 한다. 인증이 필요한 모든 요청에서 확인하면 요청마다 DB 조회가 늘어나므로,
     * 절대 만료 직후에도 이미 발급된 AccessToken은 남은 수명(app.jwt.access-time-to-live) 동안 유효하다.
     *
     * @param request 리프레시 요청 정보를 담은 [RefreshRequest] (리프레시 토큰)
     * @return 새로 발급된 AccessToken·RefreshToken을 담은 [RefreshResponse]
     * @throws BusinessException [AuthErrorCode.INVALID_REFRESH_TOKEN] 토큰이 유효하지 않거나(서명·만료·타입 불일치),
     * 저장된 리프레시 토큰이 없거나, 절대 만료가 지났거나, 이미 교체(rotate)된 토큰인 경우
     */
    @Transactional
    fun refresh(request: RefreshRequest): RefreshResponse {
        val userId = try {
            tokenService.getUserId(request.refreshToken, TokenType.REFRESH)
        } catch (e: Exception) {
            throw BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN)
        }

        val existingToken = refreshTokenRepository.findByUserId(userId)
            ?: throw BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN)

        if (existingToken.isAbsoluteExpired(Instant.now(clock))) {
            throw BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN)
        }

        if (existingToken.refreshToken != request.refreshToken) {
            throw BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN)
        }

        val (newAccessToken, newRefreshToken) = tokenService.createTokenPair(userId)

        existingToken.rotate(newRefreshToken)

        return RefreshResponse(newAccessToken, newRefreshToken)
    }

    /**
     * 사용자의 세션을 끊어 로그아웃 처리한다.
     *
     * RefreshToken 행을 지워 재발급을 막고, 무효화 기준 시각을 남겨 이미 발급된 AccessToken도 끊는다.
     * AccessToken은 서명만으로 검증되는 stateless 토큰이라 회수할 방법이 없으므로,
     * "이 시각 이전에 발급된 토큰은 거부"를 기록해두고 [TokenService]가 요청마다 확인하게 한다.
     *
     * 저장된 RefreshToken이 없으면 이미 로그아웃된 상태로 보고 정상 처리한다(멱등).
     *
     * RefreshToken 삭제를 먼저 하는 이유는, 무효화 기록만 성공하고 삭제가 롤백되면
     * 사용자가 재발급으로 iat가 새로운 AccessToken을 받아 무효화를 그대로 빠져나가기 때문이다.
     *
     * Redis 쓰기 실패는 로그만 남기고 삼킨다. 예외를 올리면 트랜잭션이 롤백되어 RefreshToken까지 되살아나
     * 로그아웃이 통째로 실패하는데, 삼키면 재발급 경로는 이미 끊긴 채 AccessToken 잔여 수명만 남기 때문이다.
     *
     * @param userId 로그아웃할 사용자 ID
     */
    @Transactional
    fun logout(userId: Long) {
        val refreshToken = refreshTokenRepository.findByUserId(userId)
            ?: return

        refreshTokenRepository.delete(refreshToken)

        try {
            tokenInvalidationRepository.createInvalidatedAt(userId, Instant.now(clock))
        } catch (e: DataAccessException) {
            logger.error("토큰 무효화 기록 실패: userId={}", userId, e)
        }
    }

    /**
     * 이메일로 6자리 인증 코드를 발급·발송하고, 인증 세션 식별용 emailChallengeToken을 발급한다.
     *
     * 용도별 전제조건을 먼저 검사한다: 회원가입은 미가입 이메일이어야 하고, 비밀번호 찾기는 가입된 이메일이어야 한다.
     * 용도는 emailChallengeToken의 클레임에 실려 confirm까지 전달된다.
     *
     * @param request 인증 코드 발송 요청 정보를 담은 [SendAuthCodeRequest] (이메일, 용도)
     * @return 발급된 emailChallengeToken을 담은 [SendAuthCodeResponse]
     * @throws BusinessException [AuthErrorCode.EMAIL_ALREADY_EXISTS] 회원가입 용도인데 이미 가입된 이메일인 경우
     * @throws BusinessException [AuthErrorCode.EMAIL_NOT_FOUND] 비밀번호 찾기 용도인데 가입되지 않은 이메일인 경우
     * @throws BusinessException [AuthErrorCode.EMAIL_SEND_RATE_LIMITED] TTL 윈도우 내 발송 횟수가 5회를 초과한 경우
     * @throws BusinessException [SesErrorCode.EMAIL_SEND_FAILED] SES 이메일 발송이 실패한 경우
     */
    fun sendAuthCode(request: SendAuthCodeRequest): SendAuthCodeResponse {
        val email = request.email
        val purpose = request.purpose

        validateEmailForPurpose(purpose, email)

        val authCode = codeService.createCodeByEmail(email, purpose)

        sesService.sendAuthCode(email, authCode)

        val emailChallengeToken = tokenService.createEmailChallengeToken(email, purpose)

        return SendAuthCodeResponse(emailChallengeToken)
    }

    /**
     * emailChallengeToken과 인증 코드를 검증하고, 토큰의 용도를 승계한 verifiedToken을 발급한다.
     *
     * 인증 코드의 유효 시간은 emailChallengeToken의 만료(exp)가 유일한 기준이며, DB에서 시간 계산은 하지 않는다.
     *
     * @param request 인증 코드 확인 요청 정보를 담은 [ConfirmAuthCodeRequest] (인증 코드, emailChallengeToken)
     * @return 발급된 verifiedToken을 담은 [ConfirmAuthCodeResponse]
     * @throws BusinessException [AuthErrorCode.EMAIL_CODE_EXPIRED] emailChallengeToken이 만료된 경우 (코드 재발송 필요)
     * @throws BusinessException [AuthErrorCode.UNAUTHORIZED] 토큰의 서명·형식·타입·용도가 유효하지 않은 경우
     * @throws BusinessException [AuthErrorCode.INCORRECT_EMAIL] 해당 이메일·용도로 발급된 인증 코드가 없는 경우
     * @throws BusinessException [AuthErrorCode.EMAIL_CODE_ATTEMPT_EXCEEDED] 오답이 5회 누적된 경우
     * @throws BusinessException [AuthErrorCode.EMAIL_CODE_MISMATCH] 인증 코드가 일치하지 않는 경우
     */
    fun confirmAuthCode(request: ConfirmAuthCodeRequest): ConfirmAuthCodeResponse {
        val challengeToken = request.emailChallengeToken
        val challengeCode = request.authCode

        val (email, purpose) = tokenService.getEmailChallengeClaims(challengeToken)

        codeService.confirmAuthCodeByEmail(challengeCode, email, purpose)

        val verifiedToken = tokenService.createVerifiedToken(
            VerifiedClaims(provider = AuthProvider.EMAIL, email = email, purpose = purpose),
        )

        return ConfirmAuthCodeResponse(verifiedToken)
    }

    /**
     * 비밀번호 찾기 용도의 verifiedToken을 검증하고, 임시 비밀번호로 교체한 뒤 이메일로 발송한다.
     *
     * verifiedToken은 소셜 로그인도 발급하므로 용도뿐 아니라 provider=EMAIL도 검사한다 —
     * 소셜 제공자가 넘긴 이메일은 우리 쪽 본인 확인을 거친 것이 아니어서 비밀번호 재설정의 근거가 될 수 없다.
     *
     * 비밀번호가 바뀌면 이전에 발급된 AccessToken도 무효화한다(로그아웃과 같은 메커니즘).
     * Redis 기록 실패는 로그만 남기고 진행한다 — 이미 커밋된 비밀번호 교체를 되돌릴 수 없기 때문이다.
     *
     * 토큰 파싱(트랜잭션 없음) → 비밀번호 교체([PasswordService] 트랜잭션) → 토큰 무효화 → 이메일 발송 순으로
     * 순차 실행된다 — 발송이 실패하면 사용자는 코드 발송부터 플로우를 재시작해야 한다.
     *
     * @param request 비밀번호 재설정 요청 정보를 담은 [PasswordResetRequest] (verifiedToken)
     * @throws BusinessException [AuthErrorCode.VERIFICATION_EXPIRED] verifiedToken이 만료된 경우
     * @throws BusinessException [AuthErrorCode.UNAUTHORIZED] 토큰의 서명·형식·타입이 유효하지 않거나, 이메일 계정의 비밀번호 찾기 용도가 아닌 경우
     * @throws BusinessException [AuthErrorCode.EMAIL_NOT_FOUND] 토큰의 이메일에 해당하는 사용자가 없는 경우
     * @throws BusinessException [SesErrorCode.EMAIL_SEND_FAILED] 임시 비밀번호 이메일 발송이 실패한 경우
     */
    fun resetPassword(request: PasswordResetRequest) {
        // TODO: 레디스 도입 시 사용한 verifiedToken을 블랙리스트로 등록해 일회성 보장

        val claims = tokenService.getVerifiedClaims(request.verifiedToken)

        if (claims.provider != AuthProvider.EMAIL || claims.purpose != AuthPurpose.RESET_PASSWORD) {
            throw BusinessException(AuthErrorCode.UNAUTHORIZED)
        }

        val email = claims.email
            ?: throw BusinessException(AuthErrorCode.UNAUTHORIZED)

        val user = userRepository.findByProviderAndEmail(AuthProvider.EMAIL, email)
            ?: throw BusinessException(AuthErrorCode.EMAIL_NOT_FOUND)

        val tempPassword = passwordService.createTempPasswordByEmail(email)

        try {
            tokenInvalidationRepository.createInvalidatedAt(user.id, Instant.now(clock))
        } catch (e: DataAccessException) {
            logger.error("토큰 무효화 기록 실패: userId={}", user.id, e)
        }

        sesService.sendTempPassword(email, tempPassword)
    }

    /**
     * 이메일 인증 용도별 전제조건을 검사한다. 이메일 인증은 EMAIL 계정 전용이므로 조회를 EMAIL로 한정한다 —
     * 같은 이메일의 소셜 계정이 있어도 회원가입은 가능하고, 비밀번호 찾기 대상은 되지 않는다.
     */
    fun validateEmailForPurpose(purpose: AuthPurpose, email: String) {
        when (purpose) {
            AuthPurpose.SIGNUP -> if (userRepository.existsByProviderAndEmail(AuthProvider.EMAIL, email)) {
                throw BusinessException(AuthErrorCode.EMAIL_ALREADY_EXISTS)
            }

            AuthPurpose.RESET_PASSWORD -> if (!userRepository.existsByProviderAndEmail(AuthProvider.EMAIL, email)) {
                throw BusinessException(AuthErrorCode.EMAIL_NOT_FOUND)
            }
        }
    }
}

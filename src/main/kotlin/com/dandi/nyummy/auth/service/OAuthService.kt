package com.dandi.nyummy.auth.service

import com.dandi.nyummy.auth.dto.OAuthLoginRequest
import com.dandi.nyummy.auth.dto.OAuthLoginResponse
import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.infra.oauth.OAuthClient
import com.dandi.nyummy.security.jwt.TokenService
import com.dandi.nyummy.security.jwt.VerifiedClaims
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.stereotype.Service

/**
 * 소셜 로그인. 제공자별 [OAuthClient]로 앱이 전달한 토큰을 검증하고, 회원 여부에 따라 로그인 또는 가입 대기로 분기한다.
 *
 * 회원가입 자체는 이메일 가입과 같은 경로([AuthService.signup])를 쓴다 — 이 서비스는 verifiedToken을 발급하는 데서 끝난다.
 */
@Service
class OAuthService(
    private val userRepository: UserRepository,
    private val tokenService: TokenService,
    private val refreshTokenService: RefreshTokenService,
    private val appLinkProperties: AppLinkProperties,
    oauthClients: List<OAuthClient>,
) {

    private val oauthClients: Map<AuthProvider, OAuthClient> = oauthClients.associateBy { it.provider }

    /**
     * 소셜 제공자의 토큰을 검증하고, 기존 회원이면 로그인 처리하고 신규면 가입 대기 토큰을 발급한다.
     *
     * 회원 식별은 (provider, providerUserId)로만 한다 — 이메일이 같아도 다른 제공자의 계정은 다른 사용자다.
     * 두 경우 모두 200이며 redirectUrl로 다음 화면을 알린다: 기존 회원은 홈(+AccessToken·RefreshToken),
     * 신규는 프로필 입력 화면(+verifiedToken). 신규 경로는 DB에 아무것도 쓰지 않는다 — 사용자 생성은 [AuthService.signup]에서.
     *
     * 트랜잭션을 걸지 않는다. 토큰 검증은 외부 I/O(공개키 조회)를 수반하므로 DB 커넥션을 잡은 채 하지 않고,
     * 기존 회원의 RefreshToken 저장만 [RefreshTokenService]가 자체 트랜잭션으로 처리한다.
     *
     * @param request 소셜 로그인 요청 정보를 담은 [OAuthLoginRequest] (제공자, 토큰, nonce)
     * @return 리다이렉트 URL과 토큰을 담은 [OAuthLoginResponse]
     * @throws BusinessException [AuthErrorCode.UNSUPPORTED_OAUTH_PROVIDER] 소셜 로그인 클라이언트가 없는 제공자인 경우
     * @throws BusinessException [AuthErrorCode.OAUTH_NONCE_REQUIRED] nonce가 필요한 제공자인데 요청에 없는 경우
     * @throws BusinessException [AuthErrorCode.INVALID_OAUTH_TOKEN] 토큰의 서명·발급자·대상·만료·nonce가 유효하지 않은 경우
     * @throws BusinessException [AuthErrorCode.OAUTH_PROVIDER_UNAVAILABLE] 제공자 공개키를 조회할 수 없는 경우
     */
    fun login(request: OAuthLoginRequest): OAuthLoginResponse {
        val oauthClient = oauthClients[request.provider]
            ?: throw BusinessException(AuthErrorCode.UNSUPPORTED_OAUTH_PROVIDER)

        val userInfo = oauthClient.getUserInfo(request.token, request.nonce)

        val user = userRepository.findByProviderAndProviderUserId(userInfo.provider, userInfo.providerUserId)

        if (user == null) {
            val verifiedToken = tokenService.createVerifiedToken(
                VerifiedClaims(
                    provider = userInfo.provider,
                    providerUserId = userInfo.providerUserId,
                    purpose = AuthPurpose.SIGNUP,
                ),
            )

            return OAuthLoginResponse(
                redirectUrl = appLinkProperties.signup,
                verifiedToken = verifiedToken,
            )
        }

        val userId = user.id

        val (accessToken, refreshToken) = tokenService.createTokenPair(userId)

        refreshTokenService.createOrRestart(userId, refreshToken)

        return OAuthLoginResponse(
            redirectUrl = appLinkProperties.home,
            accessToken = accessToken,
            refreshToken = refreshToken,
        )
    }
}

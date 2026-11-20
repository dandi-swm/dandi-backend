package com.dandi.nyummy.infra.oauth.apple

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Apple 로그인 설정.
 *
 * [clientIds]는 ID 토큰의 aud로 받을 값 목록이고, [clientId]는 탈퇴할 때 Apple에 토큰을 요청하고 취소할 때 쓰는
 * 이 앱의 식별자(iOS 번들 ID)다. [teamId], [keyId], [privateKey]는 Apple에 보낼 client secret을 서명하는 데 쓴다.
 * [privateKey]는 .p8 파일의 base64 본문이다. BEGIN, END 줄과 줄바꿈은 있어도 된다.
 */
@ConfigurationProperties(prefix = "app.oauth.apple")
class AppleOAuthProperties(
    val issuer: String,
    val jwkSetUri: String,
    val clientIds: List<String>,
    val clientId: String,
    val teamId: String,
    val keyId: String,
    val privateKey: String,
    val tokenUri: String,
    val revokeUri: String,
    val clientSecretTimeToLive: Duration,
    val connectTimeout: Duration,
    val readTimeout: Duration,
)

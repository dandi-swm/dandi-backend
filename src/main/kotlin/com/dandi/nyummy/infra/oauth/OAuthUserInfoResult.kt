package com.dandi.nyummy.infra.oauth

import com.dandi.nyummy.auth.enum.AuthProvider

data class OAuthUserInfoResult(
    val provider: AuthProvider,
    val providerUserId: String,
    val email: String?,
    val nickname: String?,
)

package com.dandi.nyummy.infra.oauth

import com.dandi.nyummy.auth.enum.AuthProvider

interface OAuthClient {

    val provider: AuthProvider

    fun getUserInfo(idToken: String, nonce: String): OAuthUserInfoResult
}

package com.dandi.nyummy.security.jwt

enum class TokenType(val value: String, val isEncrypted: Boolean) {
    ACCESS("access", false),
    REFRESH("refresh", false),
    EMAIL_CHALLENGE("emailChallenge", true),
    EMAIL_VERIFIED("emailVerified", true),
    OAUTH_VERIFIED("oauthVerified", true),
}

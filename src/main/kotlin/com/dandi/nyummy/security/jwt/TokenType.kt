package com.dandi.nyummy.security.jwt

enum class TokenType(val value: String) {
    ACCESS("access"),
    REFRESH("refresh"),
}

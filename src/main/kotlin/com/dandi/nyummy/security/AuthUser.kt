package com.dandi.nyummy.security

data class AuthUser(val userId: Long, val accessToken: String) {
    companion object {
        const val AUTHORITY = "ROLE_USER"
    }
}

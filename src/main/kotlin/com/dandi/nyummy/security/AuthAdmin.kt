package com.dandi.nyummy.security

data class AuthAdmin(val adminId: Long) {
    companion object {
        const val AUTHORITY = "ROLE_ADMIN"
    }
}

package com.dandi.nyummy.auth.dto

data class LoginResponse(val redirectUrl: String, val accessToken: String, val refreshToken: String)

package com.dandi.nyummy.auth.dto

data class EmailVerificationConfirmRequest(val email: String, val verificationCode: String)

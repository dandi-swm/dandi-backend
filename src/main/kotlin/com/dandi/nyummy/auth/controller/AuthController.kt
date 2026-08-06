package com.dandi.nyummy.auth.controller

import com.dandi.nyummy.auth.dto.EmailVerificationConfirmRequest
import com.dandi.nyummy.auth.dto.EmailVerificationRequest
import com.dandi.nyummy.auth.dto.LoginRequest
import com.dandi.nyummy.auth.dto.LoginResponse
import com.dandi.nyummy.auth.dto.SignUpRequest
import com.dandi.nyummy.auth.dto.SignUpResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<LoginResponse> = ResponseEntity.ok(
        LoginResponse(
            redirectUrl = "www.dandi.com/home",
            accessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIiwidXNlcklkIjoxLCJ0eXBlIjoiYWNjZXNzIiwia" +
                "WF0IjoxNzUzOTIwMDAwLCJleHAiOjE3NTM5MjM2MDB9.hm9KdG2zY6kA3OooLoNbUl4nwF56MJHh2ygSIq5iwHA",
            refreshToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIiwidXNlcklkIjoxLCJ0eXBlIjoicmVmcmVzaCIs" +
                "ImlhdCI6MTc1MzkyMDAwMCwiZXhwIjoxNzU1MTI5NjAwfQ.3dYJS1UPcP5ohWa4yPbdmwjd4rRwa7nSL3AadcgMFXM",
        ),
    )

    @PostMapping("/signup")
    fun signup(@RequestBody request: SignUpRequest): ResponseEntity<SignUpResponse> = ResponseEntity.ok(
        SignUpResponse(
            accessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIiwidXNlcklkIjoxLCJ0eXBlIjoiYWNjZXNzIiwia" +
                "WF0IjoxNzUzOTIwMDAwLCJleHAiOjE3NTM5MjM2MDB9.hm9KdG2zY6kA3OooLoNbUl4nwF56MJHh2ygSIq5iwHA",
            refreshToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIiwidXNlcklkIjoxLCJ0eXBlIjoicmVmcmVzaCIs" +
                "ImlhdCI6MTc1MzkyMDAwMCwiZXhwIjoxNzU1MTI5NjAwfQ.3dYJS1UPcP5ohWa4yPbdmwjd4rRwa7nSL3AadcgMFXM",
        ),
    )

    @PostMapping("/email-verification")
    fun emailVerification(@RequestBody request: EmailVerificationRequest) = ResponseEntity.ok()

    @PostMapping("/email-verification/confirm")
    fun emailVerificationConfirm(@RequestBody request: EmailVerificationConfirmRequest) = ResponseEntity.ok()
}

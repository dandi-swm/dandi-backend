package com.dandi.nyummy.user.controller

import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import com.dandi.nyummy.user.dto.PasswordUpdateRequest
import com.dandi.nyummy.user.dto.PasswordUpdateResponse
import com.dandi.nyummy.user.dto.UserResponse
import com.dandi.nyummy.user.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "User", description = "사용자 계정 API")
@RestController
@RequestMapping("/api/v1/users")
class UserController(private val userService: UserService) {
    @GetMapping("/me")
    fun me(@CurrentUser user: AuthUser): UserResponse = userService.getMe(user.userId)

    @Operation(
        summary = "비밀번호 변경",
        description = "현재 비밀번호를 확인하고 새 비밀번호로 교체한다. " +
            "기존 RefreshToken을 교체하고 AccessToken 무효화 기준 시각을 기록한다. 응답의 새 토큰 쌍으로 즉시 교체해야 한다.",
    )
    @ApiResponse(responseCode = "200", description = "비밀번호 변경 완료. 새 AccessToken·RefreshToken을 응답한다.")
    @ApiResponse(
        responseCode = "400",
        description = "현재 비밀번호가 일치하지 않거나, 새 비밀번호가 정책(8~64자, 영문·숫자 모두 포함)에 맞지 않거나, " +
            "소셜 로그인 계정이라 비밀번호가 없는 경우",
    )
    @ApiResponse(responseCode = "401", description = "인증이 필요합니다.")
    @PatchMapping("/me/password")
    fun updatePassword(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: PasswordUpdateRequest,
    ): PasswordUpdateResponse = userService.updatePassword(user.userId, request)
}

package com.dandi.nyummy.user.controller

import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import com.dandi.nyummy.user.dto.PasswordUpdateRequest
import com.dandi.nyummy.user.dto.PasswordUpdateResponse
import com.dandi.nyummy.user.dto.PushSettingResponse
import com.dandi.nyummy.user.dto.UpdateMealTimeRequest
import com.dandi.nyummy.user.dto.UpdatePushSettingRequest
import com.dandi.nyummy.user.dto.UserResponse
import com.dandi.nyummy.user.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "User", description = "내 프로필 조회 · 비밀번호 변경 · 알림 수신 설정 · 끼니 시각 설정 API")
@RestController
@RequestMapping("/api/v1/users")
class UserController(private val userService: UserService) {

    @Operation(
        summary = "내 프로필 조회",
        description = "닉네임 · 신체 정보 · 코인 · 설정한 끼니 시각을 조회한다. " +
            "알림 수신 설정은 설정 화면에서만 필요하므로 이 응답에 넣지 않고 `GET /me/push`로 분리했다.",
    )
    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없습니다.")
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

    @Operation(summary = "알림 수신 설정 조회", description = "서비스 알림과 마케팅 알림의 수신 여부를 조회한다.")
    @GetMapping("/me/push")
    fun pushSetting(@CurrentUser user: AuthUser): PushSettingResponse = userService.getPushSetting(user.userId)

    @Operation(
        summary = "알림 수신 설정 변경",
        description = "바꾸려는 항목만 담아 보낸다 — 생략한 항목은 유지된다. " +
            "서비스 알림은 기본 수신이고, 마케팅 알림은 광고성 정보로 볼 여지가 있어 기본 거부이며 " +
            "거부에서 동의로 바뀔 때 동의 시각을 기록한다.",
    )
    @ApiResponse(responseCode = "200", description = "변경 완료")
    @ApiResponse(responseCode = "404", description = "프로필을 찾을 수 없습니다.")
    @PatchMapping("/me/push")
    fun updatePushSetting(@CurrentUser user: AuthUser, @RequestBody request: UpdatePushSettingRequest) {
        userService.updatePushSetting(user.userId, request)
    }

    @Operation(
        summary = "끼니 시각 설정",
        description = "세 끼니 시각을 한 번에 교체한다. 생략하거나 null로 보낸 끼니는 알림을 받지 않는다. " +
            "각 값은 0~23이어야 하고 서로 겹칠 수 없다.",
    )
    @ApiResponse(responseCode = "400", description = "끼니 시각이 0~23이 아니거나 서로 겹칩니다.")
    @PutMapping("/me/meal-time")
    fun updateMealTime(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: UpdateMealTimeRequest,
    ): ResponseEntity<Void> {
        userService.updateMealTime(user.userId, request)
        return ResponseEntity.ok().build()
    }
}

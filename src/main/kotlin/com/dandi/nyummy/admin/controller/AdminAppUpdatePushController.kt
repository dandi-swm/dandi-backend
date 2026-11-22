package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AppUpdatePushResponse
import com.dandi.nyummy.admin.dto.SendAppUpdatePushRequest
import com.dandi.nyummy.admin.service.AdminAppUpdatePushService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.security.AuthAdmin
import com.dandi.nyummy.security.CurrentAdmin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin App Update Push", description = "어드민 앱 업데이트 알림 API (어드민 서비스 전용)")
@RestController
@RequestMapping("${AdminPaths.PREFIX}/app-update-pushes")
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminAppUpdatePushController(private val adminAppUpdatePushService: AdminAppUpdatePushService) {

    @Operation(
        summary = "앱 업데이트 알림 발송",
        description = "서비스 알림을 켠 그 플랫폼 사용자 전체에게 새 버전이 나왔다는 알림을 보낸다. 링크는 `/app-update` 앱 링크이고, 앱이 받아 자기 스토어를 연다. " +
            "사용자의 앱 버전은 보지 않아 이미 업데이트한 사용자도 받는다. " +
            "같은 플랫폼·버전으로 다시 보내면 이미 받은 사용자는 건너뛰므로, 중간에 끊겼으면 다시 보내면 된다. " +
            "8시~21시(KST)에만 보낼 수 있고, 다 보낸 뒤 건수를 응답한다.",
    )
    @ApiErrorCodes(admin = [AdminErrorCode.PUSH_NOT_ALLOWED_TIME])
    @PostMapping
    fun sendAppUpdatePushes(
        @CurrentAdmin admin: AuthAdmin,
        @Valid @RequestBody request: SendAppUpdatePushRequest,
    ): AppUpdatePushResponse = adminAppUpdatePushService.sendAppUpdatePushes(admin.adminId, request)
}

package com.dandi.nyummy.admin.controller

import com.dandi.nyummy.admin.config.AdminPaths
import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminResponse
import com.dandi.nyummy.admin.service.AdminService
import com.dandi.nyummy.security.AuthAdmin
import com.dandi.nyummy.security.CurrentAdmin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin", description = "어드민 정보 API (어드민 서비스 전용)")
@RestController
@RequestMapping(AdminPaths.PREFIX)
@SecurityRequirements
@ConditionalOnAdminEnabled
class AdminController(private val adminService: AdminService) {

    @Operation(summary = "내 어드민 정보", description = "로그인한 어드민의 정보를 조회한다. 어드민 웹이 로그인 상태를 확인할 때 쓴다.")
    @GetMapping("/me")
    fun getMe(@CurrentAdmin admin: AuthAdmin): AdminResponse = adminService.getMe(admin.adminId)
}

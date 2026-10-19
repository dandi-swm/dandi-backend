package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminResponse
import com.dandi.nyummy.admin.mapper.toAdminResponse
import com.dandi.nyummy.admin.repository.AdminRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

@Service
@ConditionalOnAdminEnabled
class AdminService(private val adminRepository: AdminRepository) {

    fun getMe(adminId: Long): AdminResponse = adminRepository.findByIdOrNull(adminId)
        ?.toAdminResponse()
        ?: throw BusinessException(AdminErrorCode.NOT_ADMIN)
}

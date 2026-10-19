package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.dto.AdminResponse
import com.dandi.nyummy.admin.entity.Admin
import com.dandi.nyummy.admin.repository.AdminRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AdminErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import io.mockk.every
import io.mockk.mockk
import org.springframework.test.util.ReflectionTestUtils
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminServiceTest {

    private val adminId = 1L

    private val adminRepository = mockk<AdminRepository>()

    private val adminService = AdminService(adminRepository)

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `어드민 정보를 반환한다`() {
        val admin = Admin(email = "admin@nyummy.co.kr", name = "운영자").apply {
            // id는 DB가 채우는 val이라 테스트에서만 직접 넣는다.
            ReflectionTestUtils.setField(this, "id", adminId)
        }
        every { adminRepository.findById(adminId) } returns Optional.of(admin)

        val response = adminService.getMe(adminId)

        assertEquals(AdminResponse(adminId = adminId, email = "admin@nyummy.co.kr", name = "운영자"), response)
    }

    @Test
    fun `어드민이 없으면 NOT_ADMIN으로 거절한다`() {
        every { adminRepository.findById(adminId) } returns Optional.empty()

        assertBusinessException(AdminErrorCode.NOT_ADMIN) {
            adminService.getMe(adminId)
        }
    }
}

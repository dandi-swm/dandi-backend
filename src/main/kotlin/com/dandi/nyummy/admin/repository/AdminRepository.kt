package com.dandi.nyummy.admin.repository

import com.dandi.nyummy.admin.entity.Admin
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AdminRepository : JpaRepository<Admin, Long> {
    fun findByEmail(email: String): Admin?

    fun existsByIdAndDisabledAtIsNull(adminId: Long): Boolean
}

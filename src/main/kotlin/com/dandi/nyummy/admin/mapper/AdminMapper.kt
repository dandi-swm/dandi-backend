package com.dandi.nyummy.admin.mapper

import com.dandi.nyummy.admin.dto.AdminResponse
import com.dandi.nyummy.admin.entity.Admin

fun Admin.toAdminResponse(): AdminResponse = AdminResponse(adminId = id, email = email, name = name)

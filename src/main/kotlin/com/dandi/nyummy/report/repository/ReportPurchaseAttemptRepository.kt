package com.dandi.nyummy.report.repository

import com.dandi.nyummy.report.entity.ReportPurchaseAttempt
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReportPurchaseAttemptRepository : JpaRepository<ReportPurchaseAttempt, Long>

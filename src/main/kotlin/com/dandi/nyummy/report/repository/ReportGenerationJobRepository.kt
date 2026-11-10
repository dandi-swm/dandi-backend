package com.dandi.nyummy.report.repository

import com.dandi.nyummy.report.entity.ReportGenerationJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReportGenerationJobRepository : JpaRepository<ReportGenerationJob, Long>

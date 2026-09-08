package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.dto.IconSummary
import com.dandi.nyummy.meal.entity.Icon
import org.springframework.data.jpa.repository.JpaRepository

interface IconRepository : JpaRepository<Icon, Long> {

    fun findAllProjectedBy(): List<IconSummary>
}

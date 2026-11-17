package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.PackageNutrition
import com.dandi.nyummy.meal.enum.NetContentUnit
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal

interface PackageNutritionRepository : JpaRepository<PackageNutrition, Long> {

    /** 용량과 단위가 null이면 용량을 읽지 못한 행(둘 다 NULL)을 찾는다. */
    fun findByNameAndNetContentAndNetContentUnit(
        name: String,
        netContent: BigDecimal?,
        netContentUnit: NetContentUnit?,
    ): PackageNutrition?
}

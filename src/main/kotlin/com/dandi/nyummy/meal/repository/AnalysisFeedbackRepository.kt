package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.AnalysisFeedback
import org.springframework.data.jpa.repository.JpaRepository

interface AnalysisFeedbackRepository : JpaRepository<AnalysisFeedback, Long> {
    fun existsByMealId(mealId: Long?): Boolean
}

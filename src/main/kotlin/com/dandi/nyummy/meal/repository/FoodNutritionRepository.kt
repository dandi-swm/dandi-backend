package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.FoodNutrition
import org.springframework.data.jpa.repository.JpaRepository

interface FoodNutritionRepository : JpaRepository<FoodNutrition, Long> {
    fun findByName(name: String): FoodNutrition?
}

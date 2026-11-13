package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.MealFood
import org.springframework.data.jpa.repository.JpaRepository

interface MealFoodRepository : JpaRepository<MealFood, Long> {

    // 식사의 음식 줄을 목록 순서대로. 맨 앞이 대표 음식이다.
    fun findAllByMealIdOrderByDisplayOrderAsc(mealId: Long): List<MealFood>
}

package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.MealFood
import org.springframework.data.jpa.repository.JpaRepository

interface MealFoodRepository : JpaRepository<MealFood, Long>

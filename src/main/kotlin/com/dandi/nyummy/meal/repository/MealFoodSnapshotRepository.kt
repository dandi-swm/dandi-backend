package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.MealFoodSnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface MealFoodSnapshotRepository : JpaRepository<MealFoodSnapshot, Long>

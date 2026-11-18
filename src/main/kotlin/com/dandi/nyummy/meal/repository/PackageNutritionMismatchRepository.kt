package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.PackageNutritionMismatch
import org.springframework.data.jpa.repository.JpaRepository

interface PackageNutritionMismatchRepository : JpaRepository<PackageNutritionMismatch, Long>

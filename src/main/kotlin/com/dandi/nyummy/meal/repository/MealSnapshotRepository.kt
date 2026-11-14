package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.MealSnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface MealSnapshotRepository : JpaRepository<MealSnapshot, Long>

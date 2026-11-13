package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.MealSnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface MealSnapshotRepository : JpaRepository<MealSnapshot, Long> {

    // 식사의 스냅샷. 식사 하나에 하나이고, 아직 인식하지 않았거나 음식이 아닌 식사는 없다.
    fun findByMealId(mealId: Long): MealSnapshot?
}

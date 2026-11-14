package com.dandi.nyummy.meal.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.math.BigDecimal
import java.time.Instant

/**
 * 식사 안의 음식 한 줄. 사용자가 고치는 지금 값이다.
 *
 * 줄 영양은 저장하지 않고 1인분([servingCalory] 등) × [portion]으로 계산한다.
 *
 * @property mealFoodSnapshotId 짝이 되는 스냅샷 줄. 사용자가 더한 줄은 null
 * @property displayOrder 목록 순서. 1이 대표 음식이고, 그 줄의 아이콘이 식사 아이콘이 된다
 * @property portion 인분
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "meal_foods")
class MealFood(
    @Column(name = "meal_id", nullable = false)
    val mealId: Long,

    @Column(name = "meal_food_snapshot_id")
    val mealFoodSnapshotId: Long?,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int,

    @Column(name = "name", nullable = false, length = 30)
    var name: String,

    @Column(name = "portion", nullable = false, precision = 2, scale = 1)
    var portion: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    // 1인분 영양. 아직 구하지 않았거나 이름이 바뀌어 다시 구해야 하는 줄은 넷 다 null이다.
    @Column(name = "serving_calory", precision = 7, scale = 2)
    var servingCalory: BigDecimal? = null

    @Column(name = "serving_carbs", precision = 7, scale = 2)
    var servingCarbs: BigDecimal? = null

    @Column(name = "serving_protein", precision = 7, scale = 2)
    var servingProtein: BigDecimal? = null

    @Column(name = "serving_fat", precision = 7, scale = 2)
    var servingFat: BigDecimal? = null

    // 사용자가 영양을 직접 고친 줄. 이름을 바꿔도 영양을 다시 구하지 않는다.
    @Column(name = "is_nutrition_fixed", nullable = false)
    var isNutritionFixed: Boolean = false

    // 이 음식의 아이콘. 1인분 영양과 함께 구하므로 그 전에는 null이다.
    @Column(name = "icon_id")
    var iconId: Long? = null

    // 사용자가 아이콘을 직접 고른 줄. 이름을 바꿔도 아이콘을 다시 구하지 않는다.
    @Column(name = "is_icon_fixed", nullable = false)
    var isIconFixed: Boolean = false

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

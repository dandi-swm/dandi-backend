package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.NetContentUnit
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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
 * @property portion 인분. 아직 구하지 않은 줄은 null이고, 영양을 계산할 때 채운다
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

    @Column(name = "portion", precision = 2, scale = 1)
    var portion: BigDecimal?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    // 1인분 영양. 아직 구하지 않았거나 이름이 바뀌어 다시 구해야 하는 줄은 넷 다 null이다.
    // 인식이 사진에서 일부만 읽은 줄은 읽은 항목만 차 있고, 빈 항목은 영양을 계산할 때 채운다.
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

    // 제품의 총 내용량과 단위. 인식이 사진에서 읽은 값이고, 제품이 아니거나 읽지 못했으면 둘 다 null이다.
    // 같은 제품도 용량에 따라 영양이 달라, 제품 이름·용량별로 저장한 영양을 찾을 때 이름과 함께 쓴다.
    @Column(name = "net_content", precision = 7, scale = 1)
    var netContent: BigDecimal? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "net_content_unit", length = 10)
    var netContentUnit: NetContentUnit? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /** 1인분 영양. 넷 중 하나라도 없으면 아직 구하지 않은 줄이라 null이다. */
    val servingNutrition: ServingNutrition?
        get() {
            return ServingNutrition(
                calory = servingCalory ?: return null,
                carbs = servingCarbs ?: return null,
                protein = servingProtein ?: return null,
                fat = servingFat ?: return null,
            )
        }

    /**
     * 사진에 적혀 있어 1인분 컬럼에 일부만 차 있는 값. 넷이 다 찼거나 하나도 없으면 null이다.
     * 영양을 계산할 때 이 값은 그대로 두고 빈 항목만 채운다.
     */
    val nutritionHint: NutritionHint?
        get() {
            val filledCount = listOfNotNull(servingCalory, servingCarbs, servingProtein, servingFat).size
            if (filledCount == 0 || filledCount == SERVING_NUTRIENT_COUNT) {
                return null
            }

            return NutritionHint(servingCalory, servingCarbs, servingProtein, servingFat)
        }

    fun updateDisplayOrder(displayOrder: Int) {
        this.displayOrder = displayOrder
    }

    fun updatePortion(portion: BigDecimal) {
        this.portion = portion
    }

    /**
     * 이름을 바꾼다. 이름이 달라지면 사용자가 고정하지 않은 1인분 영양과 아이콘을 비워, 새 이름으로 다시 구하게 한다.
     * 사용자가 직접 고친 영양과 직접 고른 아이콘은 이름을 바꿔도 그대로 둔다.
     *
     * 제품 사진에서 인식한 줄은 이름만 바꾸고 나머지는 건드리지 않는다. 그 줄의 값과 용량은 사진 속 제품의 것이라
     * 이름을 고쳐도 달라지지 않는다. 인식이 틀리기 쉬운 쪽은 이름이다(영양성분표 면만 찍으면 제품 이름이 약하게 나온다).
     * 아직 값을 구하지 않은 줄은 비어 있는 그대로라, 영양을 계산할 때 새 이름으로 구한다.
     * 제품 식사에서 사용자가 더한 줄은 사진에서 인식한 것이 아니므로 다른 줄처럼 비운다.
     *
     * 그 밖의 줄은 용량도 비운다. 사용자에게 보이지 않는 값이라 남겨 두면 모르는 사이에 다른 제품의 값을 찾는 데 쓰인다.
     *
     * @param inputCategory 이 줄이 속한 식사의 입력 유형. 모르면 null이고 제품이 아닌 것으로 본다
     */
    fun updateName(name: String, inputCategory: MealInputCategory? = null) {
        if (this.name == name) {
            return
        }

        this.name = name
        if (inputCategory?.isPackage == true && mealFoodSnapshotId != null) {
            return
        }

        this.netContent = null
        this.netContentUnit = null
        if (!isNutritionFixed) {
            this.servingCalory = null
            this.servingCarbs = null
            this.servingProtein = null
            this.servingFat = null
        }
        if (!isIconFixed) {
            this.iconId = null
        }
    }

    /** 사용자가 직접 고친 영양에서 구한 1인분을 저장하고 고정한다. 이후 이름을 바꿔도 이 값을 다시 구하지 않는다. */
    fun updateFixedServingNutrition(servingNutrition: ServingNutrition) {
        updateServingNutrition(servingNutrition)
        this.isNutritionFixed = true
    }

    /** 사용자가 직접 고른 아이콘을 저장하고 고정한다. 이후 이름을 바꿔도 이 아이콘을 다시 구하지 않는다. */
    fun updateFixedIconId(iconId: Long) {
        this.iconId = iconId
        this.isIconFixed = true
    }

    /**
     * 인식이 사진에서 읽은 힌트를 1인분 컬럼에 적는다. 적힌 항목만 채우고 나머지는 비워 둔다.
     * 넷이 다 적혀 있었으면 이 줄은 1인분 영양이 있는 줄이 되고, 일부만 있었으면 빈 항목을 영양을 계산할 때 채운다.
     */
    fun updateNutritionHint(nutritionHint: NutritionHint) {
        nutritionHint.calory?.let { this.servingCalory = it }
        nutritionHint.carbs?.let { this.servingCarbs = it }
        nutritionHint.protein?.let { this.servingProtein = it }
        nutritionHint.fat?.let { this.servingFat = it }
    }

    /**
     * 구한 1인분 영양으로 비어 있는 항목만 채운다. 사진에 적혀 있어 이미 차 있는 항목은 덮지 않는다.
     * 넷이 다 비어 있던 줄에는 [servingNutrition]이 그대로 들어간다.
     */
    fun updateEmptyServingNutrition(servingNutrition: ServingNutrition) {
        this.servingCalory = this.servingCalory ?: servingNutrition.calory
        this.servingCarbs = this.servingCarbs ?: servingNutrition.carbs
        this.servingProtein = this.servingProtein ?: servingNutrition.protein
        this.servingFat = this.servingFat ?: servingNutrition.fat
    }

    fun updateServingNutrition(servingNutrition: ServingNutrition) {
        this.servingCalory = servingNutrition.calory
        this.servingCarbs = servingNutrition.carbs
        this.servingProtein = servingNutrition.protein
        this.servingFat = servingNutrition.fat
    }

    fun updateIconId(iconId: Long) {
        this.iconId = iconId
    }

    /** 인식이 사진에서 읽은 제품의 총 내용량과 단위를 적는다. 둘은 함께 있거나 함께 없다. */
    fun updateNetContent(netContent: BigDecimal, netContentUnit: NetContentUnit) {
        this.netContent = netContent
        this.netContentUnit = netContentUnit
    }

    companion object {
        /** 식사 하나에 둘 수 있는 최대 줄 수 */
        const val MAX_COUNT = 10

        const val NAME_MAX_LENGTH = 30

        /** 인분은 [PORTION_STEP] 단위로 [MIN_PORTION]부터 [MAX_PORTION]까지다. */
        val MIN_PORTION: BigDecimal = BigDecimal("0.5")
        val MAX_PORTION: BigDecimal = BigDecimal("5.0")
        val PORTION_STEP: BigDecimal = BigDecimal("0.5")

        /** 1인분 영양의 항목 수(칼로리·탄수화물·단백질·지방) */
        private const val SERVING_NUTRIENT_COUNT = 4

        /** 인분을 따로 구하지 않는 줄에 넣는 인분(1인분) */
        val DEFAULT_PORTION: BigDecimal = BigDecimal("1.0")
    }
}

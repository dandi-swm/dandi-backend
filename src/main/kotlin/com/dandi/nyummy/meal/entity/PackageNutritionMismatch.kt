package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.enum.NetContentUnit
import com.dandi.nyummy.meal.enum.PackageNutritionMismatchCategory
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.math.BigDecimal
import java.time.Instant

/**
 * 사진에 적힌 제품의 값이 다른 값과 달랐던 기록. 쌓기만 하고 고치지 않는다.
 *
 * 저장해 둔 값을 사진 값으로 덮어쓸 때 옛 값이 여기에 남아, 잘못 덮어쓴 값을 되돌릴 수 있다.
 * 모델 값은 저장하지 않으므로, 모델 값이 표시값과 얼마나 다른지도 이 기록으로 센다.
 *
 * @property category 무엇과 달랐는지
 * @property name 그때의 제품 이름(다듬은 것)
 * @property netContent 그때의 총 내용량. [netContentUnit]과 함께 있거나 함께 없다
 * @property nutritionHintCalory 사진에 적혀 있던 값. 일부만 적혀 있던 경우가 있어 항목마다 null일 수 있다
 * @property servingCalory 비교한 쪽의 값. [PackageNutritionMismatchCategory.SAVED]면 저장해 두었던 값(덮어썼으면 덮어쓰기 전의 값),
 *   [PackageNutritionMismatchCategory.NAMED]면 모델이 구한 값이다
 * @property mealId 어느 식사에서 생긴 기록인지. 식사가 지워져도 기록은 남는다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "package_nutrition_mismatches")
class PackageNutritionMismatch(
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    val category: PackageNutritionMismatchCategory,

    @Column(name = "name", nullable = false, length = MealFood.NAME_MAX_LENGTH)
    val name: String,

    @Column(name = "net_content", precision = 7, scale = 1)
    val netContent: BigDecimal?,

    @Enumerated(EnumType.STRING)
    @Column(name = "net_content_unit", length = 10)
    val netContentUnit: NetContentUnit?,

    @Column(name = "nutrition_hint_calory", precision = 7, scale = 2)
    val nutritionHintCalory: BigDecimal?,

    @Column(name = "nutrition_hint_carbs", precision = 7, scale = 2)
    val nutritionHintCarbs: BigDecimal?,

    @Column(name = "nutrition_hint_protein", precision = 7, scale = 2)
    val nutritionHintProtein: BigDecimal?,

    @Column(name = "nutrition_hint_fat", precision = 7, scale = 2)
    val nutritionHintFat: BigDecimal?,

    @Column(name = "serving_calory", nullable = false, precision = 7, scale = 2)
    val servingCalory: BigDecimal,

    @Column(name = "serving_carbs", nullable = false, precision = 7, scale = 2)
    val servingCarbs: BigDecimal,

    @Column(name = "serving_protein", nullable = false, precision = 7, scale = 2)
    val servingProtein: BigDecimal,

    @Column(name = "serving_fat", nullable = false, precision = 7, scale = 2)
    val servingFat: BigDecimal,

    @Column(name = "meal_id", nullable = false)
    val mealId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

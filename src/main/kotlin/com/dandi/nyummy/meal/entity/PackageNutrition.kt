package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.dto.ServingNutrition
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
 * 제품 이름·용량별 영양과 아이콘. 사진의 영양성분표에서 읽은 값을 제품 이름과 용량으로 저장해 두고,
 * 같은 제품을 숫자가 보이지 않게 찍었을 때 모델에 묻지 않고 꺼내 쓴다.
 *
 * 네 값이 모두 사진에 적혀 있던 제품만 저장한다. 모델이 구한 영양 값은 표시값과 많이 달라 넣지 않는다.
 * 유효 기간은 없다. 같은 제품에서 다른 값이 읽히면 새 값으로 바꾼다.
 *
 * 이름과 용량이 같은 행은 하나다(uk_package_nutritions_name_net_content_net_content_unit).
 * 용량을 읽지 못한 제품은 용량과 단위가 둘 다 null이고, 용량이 있는 행과 서로 다른 행이다.
 *
 * @property name 다듬은 제품 이름. 앞뒤 공백을 떼고 이어진 공백을 하나로 줄이고 유니코드를 NFC로 맞춘 것이다
 * @property netContent 총 내용량. [netContentUnit]과 함께 있거나 함께 없다
 * @property iconId 그 제품의 아이콘. 사진에는 아이콘이 없어, 처음 저장할 때 모델이 고른 것이다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "package_nutritions")
class PackageNutrition(
    @Column(name = "name", nullable = false, length = MealFood.NAME_MAX_LENGTH)
    val name: String,

    @Column(name = "net_content", precision = 7, scale = 1)
    val netContent: BigDecimal?,

    @Enumerated(EnumType.STRING)
    @Column(name = "net_content_unit", length = 10)
    val netContentUnit: NetContentUnit?,

    // 제품 하나의 값. 음식 줄의 1인분(meal_foods.serving_*)으로 그대로 들어간다.
    @Column(name = "serving_calory", nullable = false, precision = 7, scale = 2)
    var servingCalory: BigDecimal,

    @Column(name = "serving_carbs", nullable = false, precision = 7, scale = 2)
    var servingCarbs: BigDecimal,

    @Column(name = "serving_protein", nullable = false, precision = 7, scale = 2)
    var servingProtein: BigDecimal,

    @Column(name = "serving_fat", nullable = false, precision = 7, scale = 2)
    var servingFat: BigDecimal,

    @Column(name = "icon_id", nullable = false)
    val iconId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    val servingNutrition: ServingNutrition
        get() = ServingNutrition(servingCalory, servingCarbs, servingProtein, servingFat)

    /** 같은 제품에서 다른 값이 읽혔을 때 그 값으로 바꾼다. 아이콘은 그대로 둔다. */
    fun updateServingNutrition(servingNutrition: ServingNutrition) {
        this.servingCalory = servingNutrition.calory
        this.servingCarbs = servingNutrition.carbs
        this.servingProtein = servingNutrition.protein
        this.servingFat = servingNutrition.fat
    }
}

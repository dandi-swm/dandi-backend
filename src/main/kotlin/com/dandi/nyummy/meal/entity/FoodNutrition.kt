package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.dto.ServingNutrition
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
import java.time.Duration
import java.time.Instant

/**
 * 음식 이름별 1인분 영양과 아이콘. 모델이 구한 값을 이름으로 저장해 두고, 같은 이름을 다시 구할 때 모델을 부르지 않고 꺼내 쓴다.
 *
 * 이름 하나에 행 하나다(uk_food_nutritions_name). 값은 유효 기간 동안 그대로 두고, 기간이 지난 뒤에 새로 구한 값으로 바꾼다.
 *
 * @property name 다듬은 음식 이름. 앞뒤 공백을 떼고 이어진 공백을 하나로 줄이고 유니코드를 NFC로 맞춘 것이다
 * @property updatedAt 값을 마지막으로 구한 시각. 유효 기간을 여기서부터 센다
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "food_nutritions")
class FoodNutrition(
    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    val name: String,

    @Column(name = "serving_calory", nullable = false, precision = 7, scale = 2)
    var servingCalory: BigDecimal,

    @Column(name = "serving_carbs", nullable = false, precision = 7, scale = 2)
    var servingCarbs: BigDecimal,

    @Column(name = "serving_protein", nullable = false, precision = 7, scale = 2)
    var servingProtein: BigDecimal,

    @Column(name = "serving_fat", nullable = false, precision = 7, scale = 2)
    var servingFat: BigDecimal,

    @Column(name = "icon_id", nullable = false)
    var iconId: Long,
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

    /**
     * 값을 구한 지 유효 기간이 지났는지. 지난 값은 없는 것으로 보고 새로 구한다.
     */
    fun isExpired(now: Instant, timeToLive: Duration): Boolean = !updatedAt.plus(timeToLive).isAfter(now)

    /**
     * 유효 기간이 지난 값을 새로 구한 값으로 바꾼다.
     *
     * 새 값이 옛 값과 같으면 바뀐 컬럼이 없어 UPDATE가 나가지 않고 [updatedAt]도 그대로 남는다. 그러면 이 행은 계속 만료된 채라
     * 같은 이름을 구할 때마다 모델을 부르게 된다. 그래서 [updatedAt]을 직접 옮겨 유효 기간을 다시 센다.
     */
    fun updateServingNutrition(servingNutrition: ServingNutrition, iconId: Long, updatedAt: Instant) {
        this.servingCalory = servingNutrition.calory
        this.servingCarbs = servingNutrition.carbs
        this.servingProtein = servingNutrition.protein
        this.servingFat = servingNutrition.fat
        this.iconId = iconId
        this.updatedAt = updatedAt
    }

    companion object {
        /** meal_foods.name과 같은 길이 */
        const val NAME_MAX_LENGTH = MealFood.NAME_MAX_LENGTH
    }
}

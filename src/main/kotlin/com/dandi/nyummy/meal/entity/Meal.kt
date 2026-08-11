package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.enum.MealStatus
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
import java.time.Instant

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "meal")
class Meal(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column
    var name: String = "",

    @Column
    var carbs: Int? = null,

    @Column
    var protein: Int? = null,

    @Column
    var fat: Int? = null,

    @Column
    var score: Int? = null,

    @Column
    var calory: Int? = null,

    @Column
    @Enumerated(EnumType.STRING)
    var status: MealStatus,

    @Column
    val imageKey: String,

    @Column
    val mealAt: Instant,

    @Column
    @CreatedDate
    var createdAt: Instant = Instant.now(),

    @Column
    @LastModifiedDate
    var updatedAt: Instant? = null,

    @Column
    var deletedAt: Instant? = null,

    @Column
    val userId: Long = 0,

    @Column
    val iconId: Long = 0,
) {

    fun updateNutrition(nutrition: Nutrition?) {
        this.calory = nutrition?.calory ?: 0
        this.carbs = nutrition?.carbs ?: 0
        this.protein = nutrition?.protein ?: 0
        this.fat = nutrition?.fat ?: 0
    }

    fun updateStatus(status: MealStatus) {
        this.status = status
    }

    fun updateName(name: String) {
        this.name = name
    }

    fun updateDeletedAt(deletedAt: Instant) {
        this.deletedAt = deletedAt
    }

    fun validateOwnership(requestUserId: Long) {
        if (userId != requestUserId) {
            throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)
        }
    }
}

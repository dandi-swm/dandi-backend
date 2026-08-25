package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisResult
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
    @Column(name = "id", nullable = false)
    val id: Long = 0L,

    @Column(name = "name", nullable = false, length = 100)
    var name: String = "",

    @Column(name = "carbs")
    var carbs: Int? = null,

    @Column(name = "protein")
    var protein: Int? = null,

    @Column(name = "fat")
    var fat: Int? = null,

    @Column(name = "score")
    var score: Int? = null,

    @Column(name = "calory")
    var calory: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: MealStatus,

    @Column(name = "image_key", nullable = false, length = 512)
    val imageKey: String,

    @Column(name = "meal_at", nullable = false)
    val mealAt: Instant,

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long = 0L,

    @Column(name = "icon_id", nullable = false)
    var iconId: Long = 0L,
) {

    fun updateAnalysisResult(analysisResult: NutritionAnalysisResult) {
        this.name = analysisResult.name
        this.iconId = analysisResult.iconId
        this.calory = analysisResult.nutrition.calory
        this.carbs = analysisResult.nutrition.carbs
        this.protein = analysisResult.nutrition.protein
        this.fat = analysisResult.nutrition.fat
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

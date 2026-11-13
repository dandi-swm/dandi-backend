package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealInputCategory
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
@Table(name = "meals")
class Meal(

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: MealStatus,

    @Column(name = "image_key", nullable = false, length = 512)
    val imageKey: String,

    @Column(name = "meal_at", nullable = false)
    val mealAt: Instant,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    // 어느 분석으로 만든 식사인지. null은 V1이다(v1 API로 만든 식사와 V0.55 전에 만든 식사).
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_version", length = 10)
    val analysisVersion: MealAnalysisVersion? = null,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "name", nullable = false, length = 100)
    var name: String = ""

    @Column(name = "carbs")
    var carbs: Int? = null

    @Column(name = "protein")
    var protein: Int? = null

    @Column(name = "fat")
    var fat: Int? = null

    @Column(name = "score")
    var score: Int? = null

    @Column(name = "calory")
    var calory: Int? = null

    @Column(name = "cat_comment", length = 255)
    var catComment: String? = null

    // 분석이 완료되어야 정해진다. 분석 전이거나 실패한 식사는 null.
    @Column(name = "icon_id")
    var iconId: Long? = null

    // 인식이 판정한 입력 유형. 다시 인식하면 바뀐다. v1 식사와 아직 인식하지 않은 식사는 null.
    @Enumerated(EnumType.STRING)
    @Column(name = "input_category", length = 30)
    var inputCategory: MealInputCategory? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    fun updateAnalysisResult(analysisResult: NutritionAnalysisResult) {
        this.name = analysisResult.name
        this.iconId = analysisResult.iconId
        this.calory = analysisResult.nutrition.calory
        this.carbs = analysisResult.nutrition.carbs
        this.protein = analysisResult.nutrition.protein
        this.fat = analysisResult.nutrition.fat
        this.catComment = analysisResult.catComment
    }

    fun updateStatus(status: MealStatus) {
        this.status = status
    }

    fun updateName(name: String) {
        this.name = name
    }

    fun updateNutrition(nutrition: Nutrition) {
        this.calory = nutrition.calory
        this.carbs = nutrition.carbs
        this.protein = nutrition.protein
        this.fat = nutrition.fat
    }

    fun updateDeletedAt(deletedAt: Instant) {
        this.deletedAt = deletedAt
    }

    fun validateOwnership(requestUserId: Long) {
        if (userId != requestUserId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }
    }

    companion object {
        /** v2 식사의 대표 이름 최대 길이. AI가 추천하는 이름과 v2 이름 수정에 쓴다. 컬럼은 100자이고 v1은 길이를 검사하지 않는다. */
        const val NAME_MAX_LENGTH = 20

        /** 고양이 코멘트 컬럼의 길이 */
        const val CAT_COMMENT_MAX_LENGTH = 255
    }
}

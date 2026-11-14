package com.dandi.nyummy.meal.entity

import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
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
import java.time.Instant

/**
 * AI가 식사를 처음 인식한 결과. 식사당 하나이고, 한 번 쓰면 바꾸지 않는다.
 * 사용자가 고친 지금 값([Meal.name], [MealFood])과 비교해 무엇을 고쳤는지 본다.
 *
 * @property name AI가 추천한 대표 이름
 * @property recognitionCategory 인식한 곳
 * @property model 인식에 쓴 모델 이름
 * @property promptVersion 인식에 쓴 프롬프트 버전
 * @property inputCategory 이 인식이 판정한 입력 유형
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "meal_snapshots")
class MealSnapshot(
    @Column(name = "meal_id", nullable = false, unique = true)
    val mealId: Long,

    @Column(name = "name", nullable = false, length = 100)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "recognition_category", nullable = false, length = 20)
    val recognitionCategory: MealRecognitionCategory,

    @Column(name = "model", nullable = false, length = 50)
    val model: String,

    @Column(name = "prompt_version", nullable = false, length = 20)
    val promptVersion: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "input_category", nullable = false, length = 30)
    val inputCategory: MealInputCategory,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

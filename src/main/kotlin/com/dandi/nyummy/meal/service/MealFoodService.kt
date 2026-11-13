package com.dandi.nyummy.meal.service

import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.entity.MealFoodSnapshot
import com.dandi.nyummy.meal.entity.MealSnapshot
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.repository.MealFoodRepository
import com.dandi.nyummy.meal.repository.MealFoodSnapshotRepository
import com.dandi.nyummy.meal.repository.MealSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 식사 안의 음식 줄과, AI가 처음 낸 목록의 스냅샷을 다룬다.
 */
@Service
class MealFoodService(
    private val mealSnapshotRepository: MealSnapshotRepository,
    private val mealFoodSnapshotRepository: MealFoodSnapshotRepository,
    private val mealFoodRepository: MealFoodRepository,
) {

    /**
     * 인식 결과를 스냅샷(AI가 처음 낸 목록)과 음식 줄(사용자가 고치는 지금 값)로 저장한다.
     * 음식 줄은 짝이 되는 스냅샷 줄을 가리키고, 순서는 인식이 낸 순서(맨 앞이 대표 음식)다.
     *
     * 식사 기록 요청 안에서 인식했을 때와 Worker의 인식 작업이 인식했을 때 모두 이 메서드로 저장한다.
     * 식사의 상태·이름을 바꾸고 작업을 만드는 일과 함께 커밋돼야 하므로 호출하는 쪽 트랜잭션에 합류한다.
     * 혼자 불려도 스냅샷만 남고 음식 줄이 빠지지 않게 트랜잭션을 건다.
     *
     * @param mealId 인식한 식사 ID. 식사 하나에 스냅샷은 하나라, 이미 스냅샷이 있는 식사에는 부르지 않는다
     * @param recognition 음식 인식 결과
     * @param recognitionCategory 인식한 곳
     * @return 저장한 음식 줄(순서대로)
     */
    @Transactional
    fun createMealFoods(
        mealId: Long,
        recognition: MealRecognitionResult,
        recognitionCategory: MealRecognitionCategory,
    ): List<MealFood> {
        val mealSnapshot = mealSnapshotRepository.save(
            MealSnapshot(
                mealId = mealId,
                name = recognition.name,
                recognitionCategory = recognitionCategory,
                model = recognition.model,
                promptVersion = recognition.promptVersion,
                inputCategory = recognition.inputCategory,
            ),
        )
        val mealFoodSnapshots = mealFoodSnapshotRepository.saveAll(
            recognition.foods.mapIndexed { index, food ->
                MealFoodSnapshot(
                    mealSnapshotId = mealSnapshot.id,
                    displayOrder = index + 1,
                    name = food.name,
                    portion = food.portion,
                )
            },
        )

        return mealFoodRepository.saveAll(
            mealFoodSnapshots.map { mealFoodSnapshot ->
                MealFood(
                    mealId = mealId,
                    mealFoodSnapshotId = mealFoodSnapshot.id,
                    displayOrder = mealFoodSnapshot.displayOrder,
                    name = mealFoodSnapshot.name,
                    portion = mealFoodSnapshot.portion,
                )
            },
        )
    }
}

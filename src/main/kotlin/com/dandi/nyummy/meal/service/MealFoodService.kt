package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CommonErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.meal.calculator.calculateServingNutrition
import com.dandi.nyummy.meal.dto.MealFoodRequest
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.entity.MealFoodSnapshot
import com.dandi.nyummy.meal.entity.MealSnapshot
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.mapper.toNutrition
import com.dandi.nyummy.meal.repository.IconRepository
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
    private val iconRepository: IconRepository,
) {

    /**
     * 식사의 음식 줄을 목록 순서대로 조회한다. 맨 앞이 대표 음식이다.
     *
     * @param mealId 조회할 식사 ID
     */
    @Transactional(readOnly = true)
    fun getMealFoods(mealId: Long): List<MealFood> = mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(mealId)

    /**
     * 1인분 조회 결과를 식사의 음식 줄에 채운다. 비어 있는 값만 채운다.
     *
     * 1인분 영양이 이미 있는 줄(사용자가 영양을 고쳐 고정한 줄, 앞서 구한 줄)의 영양과, 아이콘이 이미 있는 줄(사용자가 고른 줄,
     * 앞서 구한 줄)의 아이콘은 조회 결과가 있어도 덮지 않는다. 다시 구해야 하는 값은 음식 목록을 고칠 때 비워 둔다.
     *
     * 변경 감지로 저장하므로 호출하는 쪽 트랜잭션에 합류한다.
     *
     * @param mealId 영양을 계산하는 식사 ID
     * @param servingNutritions 음식 줄 ID별 1인분 조회 결과. 조회하지 않은 줄은 없다
     * @return 식사의 음식 줄 전체(순서대로). 조회 결과가 없는 줄도 담는다
     */
    @Transactional
    fun updateServingNutritions(mealId: Long, servingNutritions: Map<Long, ServingNutritionResult>): List<MealFood> {
        val mealFoods = mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(mealId)
        mealFoods.forEach { mealFood ->
            val servingNutritionResult = servingNutritions[mealFood.id] ?: return@forEach
            if (mealFood.servingNutrition == null) {
                mealFood.updateServingNutrition(servingNutritionResult.servingNutrition)
            }
            if (mealFood.iconId == null) {
                mealFood.updateIconId(servingNutritionResult.iconId)
            }
        }

        return mealFoods
    }

    /**
     * 식사의 음식 줄을 사용자가 보낸 목록으로 바꾼다. 목록 전체를 받고, 받은 순서가 목록 순서다(맨 앞이 대표 음식).
     *
     * - [MealFoodRequest.mealFoodId]가 있으면 그 줄을 고치고, 없으면 새 줄을 만든다. 요청에 없는 기존 줄은 지운다.
     * - 이름이 바뀐 줄은 고정하지 않은 1인분 영양과 아이콘을 비운다([MealFood.updateName]). Worker가 새 이름으로 다시 구한다.
     * - 인분만 바뀐 줄은 1인분 영양을 그대로 둔다. 줄 영양은 1인분 × 새 인분이 된다.
     * - [MealFoodRequest.nutrition]을 채운 줄은 그 값을 인분으로 나눠 1인분으로 저장하고 고정한다.
     * - [MealFoodRequest.iconId]를 채운 줄은 그 아이콘으로 고정한다.
     *
     * 영양을 다시 계산할 작업을 만들고 식사 상태를 바꾸는 일과 함께 커밋돼야 하므로 호출하는 쪽 트랜잭션에 합류한다.
     *
     * @param mealId 음식 줄을 바꿀 식사 ID
     * @param mealFoodRequests 보낸 음식 줄 전체(순서대로)
     * @return 바꾼 뒤의 음식 줄(순서대로)
     * @throws BusinessException [CommonErrorCode.INVALID_INPUT_VALUE] 없는 아이콘 id가 있거나 같은 mealFoodId가 두 번 온 경우
     * @throws BusinessException [MealErrorCode.MEAL_FOOD_NOT_FOUND] 이 식사의 줄이 아닌 mealFoodId가 있는 경우
     */
    @Transactional
    fun updateMealFoods(mealId: Long, mealFoodRequests: List<MealFoodRequest>): List<MealFood> {
        validateIconIds(mealFoodRequests)

        val mealFoodsById = mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(mealId).associateBy { it.id }
        val requestedMealFoodIds = mealFoodRequests.mapNotNull { it.mealFoodId }
        if (requestedMealFoodIds.distinct().size != requestedMealFoodIds.size) {
            throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE)
        }
        if (!mealFoodsById.keys.containsAll(requestedMealFoodIds)) {
            throw BusinessException(MealErrorCode.MEAL_FOOD_NOT_FOUND)
        }

        mealFoodRepository.deleteAllInBatch(mealFoodsById.values.filter { it.id !in requestedMealFoodIds })

        val mealFoods = mealFoodRequests.mapIndexed { index, request ->
            val mealFood = request.mealFoodId?.let { mealFoodsById.getValue(it) }
                ?: MealFood(
                    mealId = mealId,
                    mealFoodSnapshotId = null,
                    displayOrder = index + 1,
                    name = request.name,
                    portion = request.portion,
                )
            mealFood.updateDisplayOrder(index + 1)
            mealFood.updateName(request.name)
            mealFood.updatePortion(request.portion)
            request.nutrition?.let {
                mealFood.updateFixedServingNutrition(calculateServingNutrition(it.toNutrition(), request.portion))
            }
            request.iconId?.let { mealFood.updateFixedIconId(it) }

            mealFood
        }

        return mealFoodRepository.saveAll(mealFoods)
    }

    /** 사용자가 고른 아이콘이 모두 있는 아이콘인지 본다. 없는 id는 음식 줄에 저장할 수 없다. */
    private fun validateIconIds(mealFoodRequests: List<MealFoodRequest>) {
        val iconIds = mealFoodRequests.mapNotNull { it.iconId }.toSet()
        if (iconIds.isNotEmpty() && iconRepository.findAllById(iconIds).size != iconIds.size) {
            throw BusinessException(CommonErrorCode.INVALID_INPUT_VALUE)
        }
    }

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

package com.dandi.nyummy.meal.service

import com.dandi.nyummy.meal.calculator.matchesMacroNutrientCalory
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.FoodNutrition
import com.dandi.nyummy.meal.entity.PackageNutrition
import com.dandi.nyummy.meal.enum.NetContentUnit
import com.dandi.nyummy.meal.repository.FoodNutritionRepository
import com.dandi.nyummy.meal.repository.PackageNutritionRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Clock

/**
 * 이름으로 저장해 둔 영양을 찾고 저장한다. 두 가지를 다룬다.
 * - 음식 이름별 1인분 영양(food_nutritions): 모델이 구한 값을 이름으로 저장해 두어, 같은 이름은 모델을 부르지 않고 같은 값을 쓴다.
 *   유효 기간이 있다.
 * - 제품 이름·용량별 영양(package_nutritions): 사진의 영양성분표에서 읽은 값을 저장해 두어, 같은 제품을 숫자가 보이지 않게
 *   찍었을 때 모델에 묻지 않고 실제 표시값을 쓴다. 유효 기간이 없고, 모델이 구한 영양 값은 넣지 않는다.
 *
 * 메서드에 트랜잭션을 걸지 않는다. 같은 이름을 두 Worker가 동시에 처음 저장하면 한쪽이 유니크 제약에 걸리는데, 트랜잭션 안에서
 * 그 예외를 삼키면 트랜잭션이 이미 롤백으로 표시돼 커밋에서 다시 실패한다. 리포지토리 호출마다 따로 커밋하게 두고 밖에서 삼킨다.
 */
@Service
class FoodNutritionService(
    private val foodNutritionRepository: FoodNutritionRepository,
    private val packageNutritionRepository: PackageNutritionRepository,
    private val mealProperties: MealProperties,
    private val clock: Clock,
) {
    /**
     * 이름으로 저장해 둔 1인분 영양과 아이콘을 찾는다.
     *
     * @return 저장된 값이 없거나 유효 기간이 지났으면 null
     */
    fun getFoodNutrition(name: String): ServingNutritionResult? {
        val foodNutritionName = convertName(name) ?: return null
        val foodNutrition = foodNutritionRepository.findByName(foodNutritionName) ?: return null
        if (foodNutrition.isExpired(clock.instant(), mealProperties.foodNutrition.timeToLive)) {
            return null
        }

        return ServingNutritionResult(servingNutrition = foodNutrition.servingNutrition, iconId = foodNutrition.iconId)
    }

    /**
     * 모델이 구한 1인분 영양과 아이콘을 이름으로 저장한다. 같은 이름의 값이 이미 있으면 그대로 두고, 유효 기간이 지난 값만 바꾼다.
     *
     * 아래 값은 저장하지 않는다. 그 식사에는 그대로 쓰이지만 다른 식사에 다시 쓰기에는 믿을 수 없는 값이다.
     * - 음식이 아닌 이름의 값(모두 0)
     * - 칼로리가 탄단지로 계산한 칼로리와 허용 오차를 넘게 다른 값
     *
     * @param name 음식 이름. 다듬어서 저장한다
     * @param result 이름만으로 모델이 구한 값. 사진을 보고 구한 값과 사진에 적혀 있던 값은 그 식사의 값이라 넘기지 않는다
     */
    fun createFoodNutrition(name: String, result: ServingNutritionResult) {
        if (!result.isFood) {
            return
        }
        if (!matchesMacroNutrientCalory(result.servingNutrition, mealProperties.foodNutrition.caloryTolerance)) {
            logger.info("칼로리와 탄단지가 맞지 않아 음식 영양을 저장하지 않습니다: name={}, {}", name, result.servingNutrition)
            return
        }

        val foodNutritionName = convertName(name) ?: return
        val now = clock.instant()
        val foodNutrition = foodNutritionRepository.findByName(foodNutritionName)

        if (foodNutrition == null) {
            val servingNutrition = result.servingNutrition
            try {
                foodNutritionRepository.save(
                    FoodNutrition(
                        name = foodNutritionName,
                        servingCalory = servingNutrition.calory,
                        servingCarbs = servingNutrition.carbs,
                        servingProtein = servingNutrition.protein,
                        servingFat = servingNutrition.fat,
                        iconId = result.iconId,
                    ),
                )
            } catch (e: DataIntegrityViolationException) {
                // 위 조회와 저장 사이에 다른 Worker가 같은 이름을 먼저 저장했다. 먼저 들어간 값을 쓰면 되므로 넘어간다.
                logger.info("같은 이름의 음식 영양이 먼저 저장돼 넘어갑니다: name={}", foodNutritionName, e)
            }
            return
        }

        if (foodNutrition.isExpired(now, mealProperties.foodNutrition.timeToLive)) {
            foodNutrition.updateServingNutrition(result.servingNutrition, result.iconId, now)
            foodNutritionRepository.save(foodNutrition)
        }
    }

    /**
     * 제품 이름과 용량으로 저장해 둔 영양과 아이콘을 찾는다. 용량이 다르면 다른 제품이다.
     *
     * @param netContent 총 내용량. 읽지 못했으면 null이고, 용량 없이 저장한 행을 찾는다
     * @param netContentUnit 총 내용량의 단위. [netContent]와 함께 있거나 함께 없다
     * @return 저장된 값이 없으면 null
     */
    fun getPackageNutrition(
        name: String,
        netContent: BigDecimal?,
        netContentUnit: NetContentUnit?,
    ): ServingNutritionResult? {
        val packageNutrition = findPackageNutrition(name, netContent, netContentUnit) ?: return null

        return ServingNutritionResult(
            servingNutrition = packageNutrition.servingNutrition,
            iconId = packageNutrition.iconId,
        )
    }

    /**
     * 사진에서 읽은 제품의 영양과, 모델이 고른 아이콘을 제품 이름과 용량으로 저장한다.
     * 같은 제품이 이미 저장돼 있으면 그대로 둔다. 값을 바꾸는 것은 [updatePackageNutrition]이 한다.
     *
     * @param servingNutrition 사진에 적혀 있던 그 제품 하나의 값. 모델이 구한 값은 넘기지 않는다
     * @param iconId 모델이 고른 아이콘
     */
    fun createPackageNutrition(
        name: String,
        netContent: BigDecimal?,
        netContentUnit: NetContentUnit?,
        servingNutrition: ServingNutrition,
        iconId: Long,
    ) {
        val packageNutritionName = convertName(name) ?: return
        val hasNetContent = netContent != null && netContentUnit != null

        try {
            packageNutritionRepository.save(
                PackageNutrition(
                    name = packageNutritionName,
                    netContent = netContent.takeIf { hasNetContent },
                    netContentUnit = netContentUnit.takeIf { hasNetContent },
                    servingCalory = servingNutrition.calory,
                    servingCarbs = servingNutrition.carbs,
                    servingProtein = servingNutrition.protein,
                    servingFat = servingNutrition.fat,
                    iconId = iconId,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // 찾았을 때는 없었는데 그 사이 다른 Worker가 같은 제품을 먼저 저장했다. 먼저 들어간 값을 두고 넘어간다.
            logger.info(
                "같은 제품의 영양이 먼저 저장돼 넘어갑니다: name={}, netContent={}{}",
                packageNutritionName,
                netContent,
                netContentUnit,
                e,
            )
        }
    }

    /**
     * 저장해 둔 제품의 영양을 사진에서 새로 읽은 값으로 바꾼다. 아이콘은 그대로 둔다.
     * 유효 기간이 없으므로, 잘못 들어간 값이 고쳐지고 바뀐 제품을 따라가는 길이 이것뿐이다.
     *
     * @param servingNutrition 사진에 적혀 있던 그 제품 하나의 값
     */
    fun updatePackageNutrition(
        name: String,
        netContent: BigDecimal?,
        netContentUnit: NetContentUnit?,
        servingNutrition: ServingNutrition,
    ) {
        val packageNutrition = findPackageNutrition(name, netContent, netContentUnit) ?: return

        packageNutrition.updateServingNutrition(servingNutrition)
        packageNutritionRepository.save(packageNutrition)
    }

    /** 용량과 단위는 함께 있어야 한다. 하나만 있으면 용량을 읽지 못한 것으로 본다. */
    private fun findPackageNutrition(
        name: String,
        netContent: BigDecimal?,
        netContentUnit: NetContentUnit?,
    ): PackageNutrition? {
        val packageNutritionName = convertName(name) ?: return null
        val hasNetContent = netContent != null && netContentUnit != null

        return packageNutritionRepository.findByNameAndNetContentAndNetContentUnit(
            name = packageNutritionName,
            netContent = netContent.takeIf { hasNetContent },
            netContentUnit = netContentUnit.takeIf { hasNetContent },
        )
    }

    /**
     * 음식이나 제품의 이름을 저장하고 찾는 이름으로 다듬는다. 앞뒤 공백을 떼고, 이어진 공백을 하나로 줄이고, 유니코드를 NFC로 맞춘다.
     * 같은 글자가 입력 방식에 따라 다른 코드로 들어와도(자모가 풀린 한글 등) 같은 이름으로 찾기 위해서다.
     *
     * @return 다듬은 이름이 비었거나 저장할 수 있는 길이를 넘으면 null. 그런 이름은 찾지도 저장하지도 않는다
     */
    private fun convertName(name: String): String? {
        val foodNutritionName = Normalizer.normalize(name, Normalizer.Form.NFC).trim().replace(WHITESPACES, " ")

        return foodNutritionName.takeIf { it.isNotEmpty() && it.length <= FoodNutrition.NAME_MAX_LENGTH }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FoodNutritionService::class.java)

        private val WHITESPACES = Regex("\\s+")
    }
}

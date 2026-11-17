package com.dandi.nyummy.meal.service

import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.meal.config.FoodNutritionProperties
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.config.MealRecognitionProperties
import com.dandi.nyummy.meal.config.RewardBoxProperties
import com.dandi.nyummy.meal.config.TutorialRewardBoxProperties
import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.FoodNutrition
import com.dandi.nyummy.meal.entity.PackageNutrition
import com.dandi.nyummy.meal.entity.PackageNutritionMismatch
import com.dandi.nyummy.meal.enum.NetContentUnit
import com.dandi.nyummy.meal.enum.PackageNutritionMismatchCategory
import com.dandi.nyummy.meal.repository.FoodNutritionRepository
import com.dandi.nyummy.meal.repository.PackageNutritionMismatchRepository
import com.dandi.nyummy.meal.repository.PackageNutritionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 음식 이름별 1인분 영양을 이름을 다듬어 찾고, 저장해도 되는 값만 저장하며, 유효 기간이 지난 값만 바꾸는지 다룬다.
 * 제품 이름·용량별 영양은 이름을 다듬고 용량까지 맞춰 찾는지, 받은 값을 그대로 저장하고 바꾸는지 다룬다.
 * 제품의 어떤 값을 저장할지 고르는 일은 Worker가 하므로 AnalysisServiceTest가 다룬다.
 */
class FoodNutritionServiceTest {

    private val now = Instant.parse("2026-11-16T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val timeToLive = Duration.ofDays(7)

    private val mealProperties = MealProperties(
        maxDailyCount = 5,
        analysisFeedbackRate = 100,
        intakeTolerance = 0.2,
        minPositiveMacroCount = 2,
        rewardBoxes = mapOf(
            RewardBoxCategory.BRONZE to RewardBoxProperties(rate = 50, minCoinQuantity = 50, maxCoinQuantity = 69),
            RewardBoxCategory.SILVER to RewardBoxProperties(rate = 30, minCoinQuantity = 70, maxCoinQuantity = 89),
            RewardBoxCategory.GOLD to RewardBoxProperties(rate = 20, minCoinQuantity = 90, maxCoinQuantity = 100),
        ),
        rewardMailTimeToLive = Duration.ofDays(7),
        tutorialRewardBox = TutorialRewardBoxProperties(itemIds = listOf(2L, 3L), coinQuantity = 100),
        recognition = MealRecognitionProperties(
            responseTimeToLive = Duration.ofMillis(4500),
            reservedSaveTime = Duration.ofMillis(400),
            minRecognitionTime = Duration.ofSeconds(2),
            maxThreads = 30,
            nutritionDelay = Duration.ofMinutes(5),
        ),
        foodNutrition = FoodNutritionProperties(timeToLive = timeToLive, caloryTolerance = 0.1),
    )

    private val foodNutritionRepository = mockk<FoodNutritionRepository>()
    private val packageNutritionRepository = mockk<PackageNutritionRepository>()
    private val packageNutritionMismatchRepository = mockk<PackageNutritionMismatchRepository>()
    private val foodNutritionService =
        FoodNutritionService(
            foodNutritionRepository,
            packageNutritionRepository,
            packageNutritionMismatchRepository,
            mealProperties,
            clock,
        )

    // 15×4 + 15×4 + 20×9 = 300
    private val servingNutrition =
        ServingNutrition(BigDecimal("300.00"), BigDecimal("15.00"), BigDecimal("15.00"), BigDecimal("20.00"))

    private fun createFoodNutrition(name: String = "김치찌개", updatedAt: Instant = now) = FoodNutrition(
        name = name,
        servingCalory = servingNutrition.calory,
        servingCarbs = servingNutrition.carbs,
        servingProtein = servingNutrition.protein,
        servingFat = servingNutrition.fat,
        iconId = 4L,
    ).apply { this.updatedAt = updatedAt }

    private fun givenSavedFoodNutrition(foodNutrition: FoodNutrition?, name: String = "김치찌개") {
        every { foodNutritionRepository.findByName(name) } returns foodNutrition
    }

    /** 저장을 받아 저장된 엔티티를 돌려준다. */
    private fun captureSavedFoodNutrition(): () -> FoodNutrition {
        val saved = slot<FoodNutrition>()
        every { foodNutritionRepository.save(capture(saved)) } answers { saved.captured }

        return { saved.captured }
    }

    // getFoodNutrition

    @Test
    fun `저장된 값이 유효 기간 안이면 1인분과 아이콘을 돌려준다`() {
        givenSavedFoodNutrition(createFoodNutrition(updatedAt = now.minus(timeToLive).plusSeconds(1)))

        val result = foodNutritionService.getFoodNutrition("김치찌개")

        assertEquals(ServingNutritionResult(servingNutrition = servingNutrition, iconId = 4L), result)
    }

    @Test
    fun `저장된 값이 없으면 null이다`() {
        givenSavedFoodNutrition(null)

        assertNull(foodNutritionService.getFoodNutrition("김치찌개"))
    }

    @Test
    fun `저장된 값의 유효 기간이 지났으면 null이다`() {
        givenSavedFoodNutrition(createFoodNutrition(updatedAt = now.minus(timeToLive)))

        assertNull(foodNutritionService.getFoodNutrition("김치찌개"))
    }

    @Test
    fun `이름은 앞뒤 공백을 떼고 이어진 공백을 하나로 줄여 찾는다`() {
        givenSavedFoodNutrition(createFoodNutrition(name = "돼지고기 김치찌개"), name = "돼지고기 김치찌개")

        val result = foodNutritionService.getFoodNutrition("  돼지고기 \t  김치찌개\n")

        assertEquals(servingNutrition, result?.servingNutrition)
    }

    @Test
    fun `자모가 풀린 이름은 합친 이름으로 찾는다`() {
        val decomposedName = Normalizer.normalize("김치찌개", Normalizer.Form.NFD)
        givenSavedFoodNutrition(createFoodNutrition())

        val result = foodNutritionService.getFoodNutrition(decomposedName)

        assertEquals(servingNutrition, result?.servingNutrition)
        verify(exactly = 0) { foodNutritionRepository.findByName(decomposedName) }
    }

    @Test
    fun `이름이 공백뿐이거나 다듬고도 저장할 수 있는 길이를 넘으면 찾지 않는다`() {
        assertNull(foodNutritionService.getFoodNutrition("   "))
        assertNull(foodNutritionService.getFoodNutrition("가".repeat(FoodNutrition.NAME_MAX_LENGTH + 1)))

        verify(exactly = 0) { foodNutritionRepository.findByName(any()) }
    }

    // createFoodNutrition

    @Test
    fun `같은 이름의 값이 없으면 다듬은 이름으로 저장한다`() {
        givenSavedFoodNutrition(null)
        val getSaved = captureSavedFoodNutrition()

        foodNutritionService.createFoodNutrition(" 김치찌개  ", ServingNutritionResult(servingNutrition, iconId = 4L))

        val saved = getSaved()
        assertEquals("김치찌개", saved.name)
        assertEquals(servingNutrition, saved.servingNutrition)
        assertEquals(4L, saved.iconId)
    }

    @Test
    fun `같은 이름의 값이 유효 기간 안이면 그대로 둔다`() {
        givenSavedFoodNutrition(createFoodNutrition(updatedAt = now.minus(Duration.ofDays(6))))
        val other =
            ServingNutrition(BigDecimal("250.00"), BigDecimal("15.00"), BigDecimal("15.00"), BigDecimal("14.44"))

        foodNutritionService.createFoodNutrition("김치찌개", ServingNutritionResult(other, iconId = 2L))

        verify(exactly = 0) { foodNutritionRepository.save(any()) }
    }

    @Test
    fun `같은 이름의 값이 유효 기간을 지났으면 새 값으로 바꾸고 값을 구한 시각을 지금으로 옮긴다`() {
        val foodNutrition = createFoodNutrition(updatedAt = now.minus(Duration.ofDays(8)))
        givenSavedFoodNutrition(foodNutrition)
        val getSaved = captureSavedFoodNutrition()
        val other =
            ServingNutrition(BigDecimal("250.00"), BigDecimal("15.00"), BigDecimal("15.00"), BigDecimal("14.44"))

        foodNutritionService.createFoodNutrition("김치찌개", ServingNutritionResult(other, iconId = 2L))

        val saved = getSaved()
        assertEquals(other, saved.servingNutrition)
        assertEquals(2L, saved.iconId)
        assertEquals(now, saved.updatedAt)
    }

    @Test
    fun `유효 기간을 지난 값과 새 값이 같아도 값을 구한 시각을 지금으로 옮긴다`() {
        givenSavedFoodNutrition(createFoodNutrition(updatedAt = now.minus(Duration.ofDays(8))))
        val getSaved = captureSavedFoodNutrition()

        foodNutritionService.createFoodNutrition("김치찌개", ServingNutritionResult(servingNutrition, iconId = 4L))

        assertEquals(now, getSaved().updatedAt)
    }

    @Test
    fun `음식이 아닌 이름의 값은 저장하지 않는다`() {
        val zero = BigDecimal("0.00")
        val notFood = ServingNutritionResult(ServingNutrition(zero, zero, zero, zero), iconId = 5L, isFood = false)

        foodNutritionService.createFoodNutrition("의자", notFood)

        verify(exactly = 0) { foodNutritionRepository.findByName(any()) }
        verify(exactly = 0) { foodNutritionRepository.save(any()) }
    }

    @Test
    fun `칼로리가 탄단지로 계산한 칼로리와 허용 오차를 넘게 다르면 저장하지 않는다`() {
        // 탄단지 칼로리 300, 적힌 칼로리 400
        val mismatched =
            ServingNutrition(BigDecimal("400.00"), BigDecimal("15.00"), BigDecimal("15.00"), BigDecimal("20.00"))

        foodNutritionService.createFoodNutrition("김치찌개", ServingNutritionResult(mismatched, iconId = 4L))

        verify(exactly = 0) { foodNutritionRepository.save(any()) }
    }

    @Test
    fun `이름이 공백뿐이면 저장하지 않는다`() {
        foodNutritionService.createFoodNutrition("  ", ServingNutritionResult(servingNutrition, iconId = 4L))

        verify(exactly = 0) { foodNutritionRepository.save(any()) }
    }

    @Test
    fun `저장하는 사이 같은 이름이 먼저 저장돼 유니크 제약에 걸리면 넘어간다`() {
        givenSavedFoodNutrition(null)
        every { foodNutritionRepository.save(any()) } throws DataIntegrityViolationException("uk_food_nutritions_name")

        foodNutritionService.createFoodNutrition("김치찌개", ServingNutritionResult(servingNutrition, iconId = 4L))

        verify(exactly = 1) { foodNutritionRepository.save(any()) }
    }

    // 제품 이름·용량별 영양

    private val packageName = "셀렉스 프로핏 딸기초코"
    private val netContent = BigDecimal("250.0")
    private val packageServingNutrition =
        ServingNutrition(BigDecimal("100.00"), BigDecimal("4.00"), BigDecimal("20.00"), BigDecimal("0.70"))
    private val otherPackageServingNutrition =
        ServingNutrition(BigDecimal("110.00"), BigDecimal("5.00"), BigDecimal("21.00"), BigDecimal("0.80"))

    private fun createPackageNutrition(
        netContent: BigDecimal? = this.netContent,
        netContentUnit: NetContentUnit? = NetContentUnit.ML,
    ) = PackageNutrition(
        name = packageName,
        netContent = netContent,
        netContentUnit = netContentUnit,
        servingCalory = packageServingNutrition.calory,
        servingCarbs = packageServingNutrition.carbs,
        servingProtein = packageServingNutrition.protein,
        servingFat = packageServingNutrition.fat,
        iconId = 6L,
    )

    private fun givenSavedPackageNutrition(
        packageNutrition: PackageNutrition?,
        netContent: BigDecimal? = this.netContent,
        netContentUnit: NetContentUnit? = NetContentUnit.ML,
    ) {
        every {
            packageNutritionRepository.findByNameAndNetContentAndNetContentUnit(packageName, netContent, netContentUnit)
        } returns packageNutrition
    }

    // getPackageNutrition

    @Test
    fun `저장해 둔 제품의 영양과 아이콘을 이름과 용량으로 찾는다`() {
        givenSavedPackageNutrition(createPackageNutrition())

        val result = foodNutritionService.getPackageNutrition(packageName, netContent, NetContentUnit.ML)

        assertEquals(ServingNutritionResult(packageServingNutrition, 6L), result)
    }

    @Test
    fun `저장해 둔 제품의 영양이 없으면 null이다`() {
        givenSavedPackageNutrition(null)

        assertNull(foodNutritionService.getPackageNutrition(packageName, netContent, NetContentUnit.ML))
    }

    @Test
    fun `제품 이름을 다듬어 찾는다`() {
        givenSavedPackageNutrition(createPackageNutrition())

        val result = foodNutritionService.getPackageNutrition("  셀렉스  프로핏 딸기초코 ", netContent, NetContentUnit.ML)

        assertEquals(ServingNutritionResult(packageServingNutrition, 6L), result)
    }

    @Test
    fun `용량을 읽지 못한 제품은 용량 없이 저장한 행을 찾는다`() {
        givenSavedPackageNutrition(createPackageNutrition(null, null), netContent = null, netContentUnit = null)

        val result = foodNutritionService.getPackageNutrition(packageName, null, null)

        assertEquals(ServingNutritionResult(packageServingNutrition, 6L), result)
    }

    @Test
    fun `용량과 단위 가운데 하나만 있으면 용량을 읽지 못한 것으로 보고 찾는다`() {
        givenSavedPackageNutrition(createPackageNutrition(null, null), netContent = null, netContentUnit = null)

        foodNutritionService.getPackageNutrition(packageName, netContent, null)
        foodNutritionService.getPackageNutrition(packageName, null, NetContentUnit.ML)

        verify(exactly = 2) {
            packageNutritionRepository.findByNameAndNetContentAndNetContentUnit(packageName, null, null)
        }
    }

    @Test
    fun `다듬은 제품 이름이 비었거나 30자를 넘으면 찾지 않는다`() {
        assertNull(foodNutritionService.getPackageNutrition("   ", netContent, NetContentUnit.ML))
        assertNull(foodNutritionService.getPackageNutrition("가".repeat(31), netContent, NetContentUnit.ML))

        verify(exactly = 0) { packageNutritionRepository.findByNameAndNetContentAndNetContentUnit(any(), any(), any()) }
    }

    // createPackageNutrition

    @Test
    fun `사진에서 읽은 값과 아이콘을 다듬은 이름과 용량으로 저장한다`() {
        val saved = slot<PackageNutrition>()
        every { packageNutritionRepository.save(capture(saved)) } answers { firstArg() }

        foodNutritionService.createPackageNutrition(
            "  셀렉스  프로핏 딸기초코 ",
            netContent,
            NetContentUnit.ML,
            packageServingNutrition,
            6L,
        )

        assertEquals(packageName, saved.captured.name)
        assertEquals(netContent, saved.captured.netContent)
        assertEquals(NetContentUnit.ML, saved.captured.netContentUnit)
        assertEquals(packageServingNutrition, saved.captured.servingNutrition)
        assertEquals(6L, saved.captured.iconId)
    }

    @Test
    fun `용량과 단위 가운데 하나만 있으면 둘 다 비워 저장한다`() {
        val saved = mutableListOf<PackageNutrition>()
        every { packageNutritionRepository.save(capture(saved)) } answers { firstArg() }

        foodNutritionService.createPackageNutrition(packageName, netContent, null, packageServingNutrition, 6L)
        foodNutritionService.createPackageNutrition(packageName, null, NetContentUnit.ML, packageServingNutrition, 6L)

        assertEquals(listOf(null, null), saved.map { it.netContent })
        assertEquals(listOf(null, null), saved.map { it.netContentUnit })
    }

    @Test
    fun `같은 제품을 다른 Worker가 먼저 저장했으면 넘어간다`() {
        every { packageNutritionRepository.save(any()) } throws DataIntegrityViolationException("uk")

        foodNutritionService.createPackageNutrition(
            packageName,
            netContent,
            NetContentUnit.ML,
            packageServingNutrition,
            6L,
        )

        verify(exactly = 1) { packageNutritionRepository.save(any()) }
    }

    @Test
    fun `다듬은 제품 이름이 비었거나 30자를 넘으면 저장하지 않는다`() {
        foodNutritionService.createPackageNutrition("   ", netContent, NetContentUnit.ML, packageServingNutrition, 6L)
        foodNutritionService.createPackageNutrition(
            "가".repeat(31),
            netContent,
            NetContentUnit.ML,
            packageServingNutrition,
            6L,
        )

        verify(exactly = 0) { packageNutritionRepository.save(any()) }
    }

    // updatePackageNutrition

    @Test
    fun `저장해 둔 제품의 영양을 새로 읽은 값으로 바꾸고 아이콘은 그대로 둔다`() {
        val packageNutrition = createPackageNutrition()
        givenSavedPackageNutrition(packageNutrition)
        every { packageNutritionRepository.save(packageNutrition) } answers { firstArg() }

        foodNutritionService.updatePackageNutrition(
            packageName,
            netContent,
            NetContentUnit.ML,
            otherPackageServingNutrition,
        )

        assertEquals(otherPackageServingNutrition, packageNutrition.servingNutrition)
        assertEquals(6L, packageNutrition.iconId)
        verify(exactly = 1) { packageNutritionRepository.save(packageNutrition) }
    }

    @Test
    fun `바꾸려는 제품이 저장돼 있지 않으면 아무것도 하지 않는다`() {
        givenSavedPackageNutrition(null)

        foodNutritionService.updatePackageNutrition(
            packageName,
            netContent,
            NetContentUnit.ML,
            otherPackageServingNutrition,
        )

        verify(exactly = 0) { packageNutritionRepository.save(any()) }
    }

    // createPackageNutritionMismatch

    @Test
    fun `사진 값과 비교한 값을 다듬은 이름과 용량, 식사 id와 함께 기록한다`() {
        val saved = slot<PackageNutritionMismatch>()
        every { packageNutritionMismatchRepository.save(capture(saved)) } answers { firstArg() }

        foodNutritionService.createPackageNutritionMismatch(
            category = PackageNutritionMismatchCategory.SAVED,
            name = "  셀렉스  프로핏 딸기초코 ",
            netContent = netContent,
            netContentUnit = NetContentUnit.ML,
            nutritionHint = NutritionHint(
                otherPackageServingNutrition.calory,
                otherPackageServingNutrition.carbs,
                otherPackageServingNutrition.protein,
                otherPackageServingNutrition.fat,
            ),
            servingNutrition = packageServingNutrition,
            mealId = 100L,
        )

        val mismatch = saved.captured
        assertEquals(PackageNutritionMismatchCategory.SAVED, mismatch.category)
        assertEquals(packageName, mismatch.name)
        assertEquals(netContent, mismatch.netContent)
        assertEquals(NetContentUnit.ML, mismatch.netContentUnit)
        assertEquals(otherPackageServingNutrition.calory, mismatch.nutritionHintCalory)
        assertEquals(otherPackageServingNutrition.carbs, mismatch.nutritionHintCarbs)
        assertEquals(otherPackageServingNutrition.protein, mismatch.nutritionHintProtein)
        assertEquals(otherPackageServingNutrition.fat, mismatch.nutritionHintFat)
        assertEquals(packageServingNutrition.calory, mismatch.servingCalory)
        assertEquals(packageServingNutrition.carbs, mismatch.servingCarbs)
        assertEquals(packageServingNutrition.protein, mismatch.servingProtein)
        assertEquals(packageServingNutrition.fat, mismatch.servingFat)
        assertEquals(100L, mismatch.mealId)
    }

    @Test
    fun `사진에 일부만 적혀 있던 값은 적힌 항목만 기록하고 용량이 없으면 비워 둔다`() {
        val saved = slot<PackageNutritionMismatch>()
        every { packageNutritionMismatchRepository.save(capture(saved)) } answers { firstArg() }

        foodNutritionService.createPackageNutritionMismatch(
            category = PackageNutritionMismatchCategory.SAVED,
            name = packageName,
            netContent = netContent,
            netContentUnit = null,
            nutritionHint = NutritionHint(calory = BigDecimal("120.00"), protein = BigDecimal("20.00")),
            servingNutrition = packageServingNutrition,
            mealId = 100L,
        )

        val mismatch = saved.captured
        assertEquals(BigDecimal("120.00"), mismatch.nutritionHintCalory)
        assertNull(mismatch.nutritionHintCarbs)
        assertEquals(BigDecimal("20.00"), mismatch.nutritionHintProtein)
        assertNull(mismatch.nutritionHintFat)
        assertNull(mismatch.netContent)
        assertNull(mismatch.netContentUnit)
    }

    @Test
    fun `다듬은 제품 이름이 비었거나 30자를 넘으면 기록하지 않는다`() {
        listOf("   ", "가".repeat(31)).forEach { invalidName ->
            foodNutritionService.createPackageNutritionMismatch(
                category = PackageNutritionMismatchCategory.NAMED,
                name = invalidName,
                netContent = netContent,
                netContentUnit = NetContentUnit.ML,
                nutritionHint = NutritionHint(calory = BigDecimal("120.00")),
                servingNutrition = packageServingNutrition,
                mealId = 100L,
            )
        }

        verify(exactly = 0) { packageNutritionMismatchRepository.save(any()) }
    }
}

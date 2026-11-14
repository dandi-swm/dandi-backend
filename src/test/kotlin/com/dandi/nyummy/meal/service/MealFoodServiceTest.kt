package com.dandi.nyummy.meal.service

import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.entity.MealFoodSnapshot
import com.dandi.nyummy.meal.entity.MealSnapshot
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.repository.MealFoodRepository
import com.dandi.nyummy.meal.repository.MealFoodSnapshotRepository
import com.dandi.nyummy.meal.repository.MealSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verifyOrder
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealFoodServiceTest {

    private val mealId = 7L
    private val recognition = MealRecognitionResult(
        inputCategory = MealInputCategory.FOOD_PHOTO,
        name = "불고기 백반",
        foods = listOf(
            MealFoodRecognitionResult(name = "불고기", portion = BigDecimal("1.5")),
            MealFoodRecognitionResult(name = "잡곡밥", portion = BigDecimal("1.0")),
        ),
        model = "gemini-recognition-test",
        promptVersion = "v1",
    )

    private val mealSnapshotRepository = mockk<MealSnapshotRepository>()
    private val mealFoodSnapshotRepository = mockk<MealFoodSnapshotRepository>()
    private val mealFoodRepository = mockk<MealFoodRepository>()

    private val mealFoodService = MealFoodService(
        mealSnapshotRepository = mealSnapshotRepository,
        mealFoodSnapshotRepository = mealFoodSnapshotRepository,
        mealFoodRepository = mealFoodRepository,
    )

    private val mealSnapshot = slot<MealSnapshot>()
    private val mealFoodSnapshots = slot<List<MealFoodSnapshot>>()

    init {
        every { mealSnapshotRepository.save(capture(mealSnapshot)) } answers { firstArg() }
        every { mealFoodSnapshotRepository.saveAll(capture(mealFoodSnapshots)) } answers { firstArg() }
        every { mealFoodRepository.saveAll(any<List<MealFood>>()) } answers { firstArg() }
    }

    // createMealFoods

    @Test
    fun `인식 결과의 대표 이름과 유형, 모델, 프롬프트 버전을 스냅샷에 남긴다`() {
        mealFoodService.createMealFoods(mealId, recognition, MealRecognitionCategory.SYNC)

        assertEquals(mealId, mealSnapshot.captured.mealId)
        assertEquals("불고기 백반", mealSnapshot.captured.name)
        assertEquals("gemini-recognition-test", mealSnapshot.captured.model)
        assertEquals("v1", mealSnapshot.captured.promptVersion)
        assertEquals(MealInputCategory.FOOD_PHOTO, mealSnapshot.captured.inputCategory)
    }

    @Test
    fun `스냅샷에 인식한 곳을 받은 그대로 남긴다`() {
        mealFoodService.createMealFoods(mealId, recognition, MealRecognitionCategory.WORKER)

        assertEquals(MealRecognitionCategory.WORKER, mealSnapshot.captured.recognitionCategory)
    }

    @Test
    fun `스냅샷 줄과 음식 줄을 인식한 순서대로 저장하고 음식 줄을 돌려준다`() {
        val mealFoods = mealFoodService.createMealFoods(mealId, recognition, MealRecognitionCategory.SYNC)

        assertEquals(
            listOf(Triple(1, "불고기", BigDecimal("1.5")), Triple(2, "잡곡밥", BigDecimal("1.0"))),
            mealFoodSnapshots.captured.map { Triple(it.displayOrder, it.name, it.portion) },
        )
        assertEquals(
            listOf(Triple(1, "불고기", BigDecimal("1.5")), Triple(2, "잡곡밥", BigDecimal("1.0"))),
            mealFoods.map { Triple(it.displayOrder, it.name, it.portion) },
        )
        assertTrue(mealFoods.all { it.mealId == mealId })
    }

    @Test
    fun `음식 줄은 아직 영양과 아이콘을 구하지 않았고 고정되지 않은 채로 저장한다`() {
        val mealFoods = mealFoodService.createMealFoods(mealId, recognition, MealRecognitionCategory.SYNC)

        assertTrue(mealFoods.all { it.servingNutrition == null && it.iconId == null })
        assertTrue(mealFoods.none { it.isNutritionFixed || it.isIconFixed })
    }

    @Test
    fun `스냅샷, 스냅샷 줄, 음식 줄 순서로 저장한다`() {
        // 스냅샷 줄은 스냅샷의 id를, 음식 줄은 스냅샷 줄의 id를 가리키므로 이 순서여야 한다.
        mealFoodService.createMealFoods(mealId, recognition, MealRecognitionCategory.SYNC)

        verifyOrder {
            mealSnapshotRepository.save(any())
            mealFoodSnapshotRepository.saveAll(any<List<MealFoodSnapshot>>())
            mealFoodRepository.saveAll(any<List<MealFood>>())
        }
    }

    // getMealFoods

    @Test
    fun `음식 줄은 목록 순서대로 조회한다`() {
        val mealFoods = listOf(createMealFood(displayOrder = 1, name = "불고기"))
        every { mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(mealId) } returns mealFoods

        assertEquals(mealFoods, mealFoodService.getMealFoods(mealId))
    }

    // updateServingNutritions

    private val oldServingNutrition = createServingNutrition("100")
    private val newServingNutrition = createServingNutrition("350")

    private fun createServingNutrition(calory: String) = ServingNutrition(
        calory = BigDecimal(calory),
        carbs = BigDecimal("15.5"),
        protein = BigDecimal("25.25"),
        fat = BigDecimal("20"),
    )

    /** id가 생성자 밖의 val이라 mockk로 id만 흉내 내고, 나머지는 실제 엔티티 그대로 둔다. */
    private fun createMealFood(
        displayOrder: Int,
        name: String,
        id: Long = displayOrder.toLong(),
        servingNutrition: ServingNutrition? = null,
        iconId: Long? = null,
    ): MealFood = spyk(
        MealFood(
            mealId = mealId,
            mealFoodSnapshotId = null,
            displayOrder = displayOrder,
            name = name,
            portion = BigDecimal("1.0"),
        ),
    ).apply {
        every { this@apply.id } returns id
        if (servingNutrition != null) updateServingNutrition(servingNutrition)
        if (iconId != null) updateIconId(iconId)
    }

    private fun givenMealFoods(vararg mealFoods: MealFood) {
        every { mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(mealId) } returns mealFoods.toList()
    }

    @Test
    fun `1인분과 아이콘이 비어 있는 줄에 조회 결과를 채운다`() {
        val mealFood = createMealFood(displayOrder = 1, name = "불고기")
        givenMealFoods(mealFood)

        mealFoodService.updateServingNutritions(mealId, mapOf(1L to ServingNutritionResult(newServingNutrition, 12L)))

        assertEquals(newServingNutrition, mealFood.servingNutrition)
        assertEquals(12L, mealFood.iconId)
    }

    @Test
    fun `1인분이 이미 있는 줄은 영양을 덮지 않고 비어 있는 아이콘만 채운다`() {
        // 사용자가 영양을 고쳐 고정했고, 이름을 바꿔 아이콘만 다시 구해야 하는 줄
        val mealFood = createMealFood(displayOrder = 1, name = "불고기", servingNutrition = oldServingNutrition)
        givenMealFoods(mealFood)

        mealFoodService.updateServingNutritions(mealId, mapOf(1L to ServingNutritionResult(newServingNutrition, 12L)))

        assertEquals(oldServingNutrition, mealFood.servingNutrition)
        assertEquals(12L, mealFood.iconId)
    }

    @Test
    fun `아이콘이 이미 있는 줄은 아이콘을 덮지 않고 비어 있는 1인분만 채운다`() {
        // 사용자가 아이콘을 골라 고정했고, 이름을 바꿔 영양만 다시 구해야 하는 줄
        val mealFood = createMealFood(displayOrder = 1, name = "불고기", iconId = 7L)
        givenMealFoods(mealFood)

        mealFoodService.updateServingNutritions(mealId, mapOf(1L to ServingNutritionResult(newServingNutrition, 12L)))

        assertEquals(newServingNutrition, mealFood.servingNutrition)
        assertEquals(7L, mealFood.iconId)
    }

    @Test
    fun `조회 결과가 없는 줄은 그대로 둔다`() {
        val lookedUp = createMealFood(displayOrder = 1, name = "불고기")
        val untouched =
            createMealFood(displayOrder = 2, name = "잡곡밥", servingNutrition = oldServingNutrition, iconId = 5L)
        val empty = createMealFood(displayOrder = 3, name = "김치")
        givenMealFoods(lookedUp, untouched, empty)

        mealFoodService.updateServingNutritions(mealId, mapOf(1L to ServingNutritionResult(newServingNutrition, 12L)))

        assertEquals(oldServingNutrition, untouched.servingNutrition)
        assertEquals(5L, untouched.iconId)
        assertNull(empty.servingNutrition)
        assertNull(empty.iconId)
    }

    @Test
    fun `조회 결과가 없는 줄까지 식사의 음식 줄 전체를 순서대로 돌려준다`() {
        val first = createMealFood(displayOrder = 1, name = "불고기")
        val second = createMealFood(displayOrder = 2, name = "잡곡밥", servingNutrition = oldServingNutrition, iconId = 5L)
        givenMealFoods(first, second)

        val mealFoods = mealFoodService.updateServingNutritions(
            mealId,
            mapOf(1L to ServingNutritionResult(newServingNutrition, 12L)),
        )

        assertEquals(listOf(first, second), mealFoods)
    }
}

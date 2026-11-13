package com.dandi.nyummy.meal.service

import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionResult
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
import io.mockk.verifyOrder
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.createGeminiImagePart
import com.dandi.nyummy.meal.dto.IconSummaryResult
import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.PhotoServingNutritionInput
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.entity.Icon
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.service.IconService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.QueryTimeoutException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 모델이 낸 1인분 값을 요청한 음식과 짝짓고 저장할 수 있는 모양으로 맞추는지, 입력 유형에 맞는 프롬프트를 고르는지 다룬다.
 * Gemini 호출과 아이콘 조회는 mockk로 흉내 낸다.
 */
class GeminiServingNutritionClientTest {

    private val aiProperties = AiProperties(
        apiKey = "test-api-key",
        model = "gemini-test",
        recognitionModel = "gemini-recognition-test",
        servingNutritionModel = "gemini-serving-nutrition-test",
        photoNutritionModel = "gemini-photo-nutrition-test",
        catCommentModel = "gemini-cat-comment-test",
        baseUrl = "https://gemini.test",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(1),
        maxConnections = 1,
        connectionRequestTimeout = Duration.ofSeconds(1),
    )

    private val geminiClient = mockk<GeminiClient>()
    private val iconService = mockk<IconService>()
    private val objectMapper = jacksonObjectMapper()
    private val client = GeminiServingNutritionClient(geminiClient, aiProperties, iconService, objectMapper)

    init {
        every { iconService.getIcons() } returns
            listOf(IconSummaryResult(id = 5L, name = "밥"), IconSummaryResult(id = 12L, name = "고기"))
    }

    private fun assertAnalysisFailure(expected: AnalysisFailureCategory, block: () -> Unit) {
        val exception = assertFailsWith<AnalysisFailureException>(block = block)
        assertEquals(expected, exception.category)
    }

    private fun createServing(
        index: Int,
        calory: Number = 350,
        carbs: Number = 15,
        protein: Number = 25,
        fat: Number = 20,
        iconId: Long = 12L,
    ) = mapOf(
        "index" to index,
        "calory" to calory,
        "carbs" to carbs,
        "protein" to protein,
        "fat" to fat,
        "iconId" to iconId,
    )

    private fun givenServings(vararg servings: Map<String, Any>) {
        every { geminiClient.createContent(any(), any(), any(), any()) } returns
            objectMapper.writeValueAsString(mapOf("foods" to servings.toList()))
    }

    /** [names]와 [inputCategory]로 조회했을 때 Gemini에 넘긴 프롬프트. */
    private fun capturePrompt(names: List<String>, inputCategory: MealInputCategory): String {
        val parts = slot<List<Map<String, Any>>>()
        every { geminiClient.createContent(any(), capture(parts), any(), any()) } returns
            objectMapper.writeValueAsString(mapOf("foods" to names.indices.map { createServing(index = it + 1) }))

        client.getServingNutritions(names, inputCategory)

        return parts.captured.single().getValue("text") as String
    }

    @Test
    fun `결과를 번호로 짝지어 요청한 음식 순서대로 돌려준다`() {
        givenServings(
            createServing(index = 2, calory = 300, carbs = 65, protein = 6, fat = 1, iconId = 5L),
            createServing(index = 1, calory = 350, carbs = 15, protein = 25, fat = 20, iconId = 12L),
        )

        val results = client.getServingNutritions(listOf("불고기", "잡곡밥"), MealInputCategory.FOOD_PHOTO)

        assertEquals(
            listOf(
                ServingNutrition(BigDecimal("350.00"), BigDecimal("15.00"), BigDecimal("25.00"), BigDecimal("20.00")),
                ServingNutrition(BigDecimal("300.00"), BigDecimal("65.00"), BigDecimal("6.00"), BigDecimal("1.00")),
            ),
            results.map { it.servingNutrition },
        )
        assertEquals(listOf(12L, 5L), results.map { it.iconId })
    }

    @Test
    fun `1인분 값을 소수 둘째 자리로 맞춘다`() {
        givenServings(createServing(index = 1, calory = 276.666, carbs = 15.004, protein = 25.005, fat = 20))

        val result = client.getServingNutritions(listOf("불고기"), MealInputCategory.FOOD_PHOTO).single()

        assertEquals(
            ServingNutrition(BigDecimal("276.67"), BigDecimal("15.00"), BigDecimal("25.01"), BigDecimal("20.00")),
            result.servingNutrition,
        )
    }

    @Test
    fun `요청한 음식의 번호가 결과에 없으면 INVALID_RESPONSE다`() {
        givenServings(createServing(index = 1))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            client.getServingNutritions(listOf("불고기", "잡곡밥"), MealInputCategory.FOOD_PHOTO)
        }
    }

    @Test
    fun `1인분 값이 음수면 INVALID_RESPONSE다`() {
        givenServings(createServing(index = 1, fat = -1))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            client.getServingNutritions(listOf("불고기"), MealInputCategory.FOOD_PHOTO)
        }
    }

    @Test
    fun `1인분 값이 저장할 수 없게 크면 INVALID_RESPONSE다`() {
        givenServings(createServing(index = 1, calory = 100000))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            client.getServingNutritions(listOf("불고기"), MealInputCategory.FOOD_PHOTO)
        }
    }

    @Test
    fun `아이콘이 목록에 없으면 기본 아이콘으로 바꾼다`() {
        givenServings(createServing(index = 1, iconId = 999L))

        val result = client.getServingNutritions(listOf("불고기"), MealInputCategory.FOOD_PHOTO).single()

        assertEquals(5L, result.iconId)
    }

    @Test
    fun `결과가 JSON이 아니면 INVALID_RESPONSE다`() {
        every { geminiClient.createContent(any(), any(), any(), any()) } returns "JSON이 아님"

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            client.getServingNutritions(listOf("불고기"), MealInputCategory.FOOD_PHOTO)
        }
    }

    @Test
    fun `음식 이름이 없으면 Gemini를 부르지 않는다`() {
        val results = client.getServingNutritions(emptyList(), MealInputCategory.FOOD_PHOTO)

        assertTrue(results.isEmpty())
        verify(exactly = 0) { geminiClient.createContent(any(), any(), any(), any()) }
    }

    @Test
    fun `아이콘 목록을 조회하지 못하면 UNAVAILABLE이다`() {
        every { iconService.getIcons() } throws QueryTimeoutException("조회 시간 초과")

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) {
            client.getServingNutritions(listOf("불고기"), MealInputCategory.FOOD_PHOTO)
        }
    }

    @Test
    fun `프롬프트에 번호를 붙인 음식 목록과 아이콘 목록을 넣는다`() {
        val prompt = capturePrompt(listOf("불고기", "잡곡\n밥"), MealInputCategory.FOOD_PHOTO)

        assertContains(prompt, "1. 불고기\n2. 잡곡 밥")
        assertContains(prompt, """[{"id":5,"name":"밥"},{"id":12,"name":"고기"}]""")
    }

    @Test
    fun `제품과 영양성분표는 그 제품의 영양성분표 값을 묻는다`() {
        listOf(MealInputCategory.PACKAGE, MealInputCategory.NUTRITION_LABEL).forEach { inputCategory ->
            assertContains(capturePrompt(listOf("신라면 큰사발"), inputCategory), "그 제품의 영양성분표에 적힌 값")
        }
    }

    @Test
    fun `그 밖의 입력 유형은 일반적인 1인분을 묻는다`() {
        val inputCategories = MealInputCategory.entries - MealInputCategory.PACKAGE - MealInputCategory.NUTRITION_LABEL

        inputCategories.forEach { inputCategory ->
            assertContains(capturePrompt(listOf("불고기"), inputCategory), "일반적인 1인분 영양 정보")
        }
    }

    @Test
    fun `음식이 아닌 이름은 모델이 값을 적어도 1인분을 모두 0으로, 아이콘을 기본 아이콘으로 돌려준다`() {
        givenServings(
            createServing(index = 1, calory = 120, carbs = 3, protein = 1, fat = 2, iconId = 12L) +
                mapOf("isFood" to false),
            createServing(index = 2, calory = 300, iconId = 5L) + mapOf("isFood" to true),
        )

        val results = client.getServingNutritions(listOf("의자", "잡곡밥"), MealInputCategory.TEXT_RECORD)

        val notFood = results.first()
        val zero = BigDecimal("0.00")
        assertEquals(ServingNutrition(zero, zero, zero, zero), notFood.servingNutrition)
        assertEquals(Icon.DEFAULT_ID, notFood.iconId)
        assertFalse(notFood.isFood)
        assertEquals(BigDecimal("300.00"), results.last().servingNutrition.calory)
        assertTrue(results.last().isFood)
    }

    @Test
    fun `음식이 아닌 이름은 값이 저장할 수 없는 값이어도 실패하지 않는다`() {
        givenServings(createServing(index = 1, calory = -1) + mapOf("isFood" to false))

        val result = client.getServingNutritions(listOf("asdf"), MealInputCategory.TEXT_RECORD).single()

        assertFalse(result.isFood)
    }

    @Test
    fun `결과에 isFood가 없으면 음식으로 본다`() {
        givenServings(createServing(index = 1))

        val result = client.getServingNutritions(listOf("불고기"), MealInputCategory.FOOD_PHOTO).single()

        assertTrue(result.isFood)
    }

    @Test
    fun `응답 스키마는 음식마다 isFood를 반드시 받는다`() {
        val responseSchema = slot<Map<String, Any>>()
        every { geminiClient.createContent(any(), any(), capture(responseSchema), any()) } returns
            objectMapper.writeValueAsString(mapOf("foods" to listOf(createServing(index = 1))))

        client.getServingNutritions(listOf("불고기"), MealInputCategory.FOOD_PHOTO)

        val foods = (responseSchema.captured["properties"] as Map<*, *>)["foods"] as Map<*, *>
        val foodSchema = foods["items"] as Map<*, *>
        assertEquals(mapOf("type" to "BOOLEAN"), (foodSchema["properties"] as Map<*, *>)["isFood"])
        assertContains(foodSchema["required"] as List<*>, "isFood")
    }

    // getPhotoServingNutritions

    private val imageBytes = byteArrayOf(1, 2, 3)
    private val contentType = "image/jpeg"

    private fun createPhotoServing(index: Int, portion: String = "1", calory: Number = 350, iconId: Long = 12L) =
        createServing(index = index, calory = calory, iconId = iconId) + mapOf("portion" to portion)

    private fun givenPhotoServings(vararg servings: Map<String, Any>, total: Map<String, Any?> = emptyMap()) {
        every { geminiClient.createContent(any(), any(), any(), any(), any()) } returns
            objectMapper.writeValueAsString(mapOf("foods" to servings.toList(), "total" to total))
    }

    private fun getPhotoServingNutritions(vararg foods: PhotoServingNutritionInput) =
        client.getPhotoServingNutritions(imageBytes, contentType, foods.toList())

    @Test
    fun `사진을 함께 보낸 조회는 음식마다 1인분과 인분과 아이콘을 요청한 순서대로 돌려준다`() {
        // 모델이 순서를 바꿔 답해도 번호로 짝짓는다.
        givenPhotoServings(
            createPhotoServing(index = 2, portion = "1", calory = 300, iconId = 5L),
            createPhotoServing(index = 1, portion = "1.5", calory = 350, iconId = 12L),
        )

        val result = getPhotoServingNutritions(PhotoServingNutritionInput("불고기"), PhotoServingNutritionInput("잡곡밥"))

        assertEquals(
            listOf(BigDecimal("350.00"), BigDecimal("300.00")),
            result.servingNutritions.map {
                it.servingNutrition.calory
            },
        )
        assertEquals(listOf(BigDecimal("1.5"), BigDecimal("1.0")), result.servingNutritions.map { it.portion })
        assertEquals(listOf(12L, 5L), result.servingNutritions.map { it.iconId })
    }

    @Test
    fun `인분이 0_5 단위가 아니거나 범위를 벗어나면 가장 가까운 0_5 단위로 0_5에서 5 사이에 맞춘다`() {
        givenPhotoServings(
            createPhotoServing(index = 1, portion = "0.8"),
            createPhotoServing(index = 2, portion = "0.1"),
            createPhotoServing(index = 3, portion = "7"),
        )

        val result = getPhotoServingNutritions(
            PhotoServingNutritionInput("불고기"),
            PhotoServingNutritionInput("잡곡밥"),
            PhotoServingNutritionInput("김치"),
        )

        assertEquals(
            listOf(BigDecimal("1.0"), BigDecimal("0.5"), BigDecimal("5.0")),
            result.servingNutritions.map { it.portion },
        )
    }

    @Test
    fun `인분이 숫자가 아니면 INVALID_RESPONSE다`() {
        givenPhotoServings(createPhotoServing(index = 1, portion = "많이"))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            getPhotoServingNutritions(PhotoServingNutritionInput("불고기"))
        }
    }

    @Test
    fun `사진에 적힌 식사 전체의 값을 읽어 소수 둘째 자리로 돌려준다`() {
        givenPhotoServings(
            createPhotoServing(index = 1),
            total = mapOf("calory" to 409, "carbs" to 62, "protein" to 32.7, "fat" to 2.2),
        )

        val result = getPhotoServingNutritions(PhotoServingNutritionInput("닭안심살"))

        assertEquals(
            NutritionHint(BigDecimal("409.00"), BigDecimal("62.00"), BigDecimal("32.70"), BigDecimal("2.20")),
            result.totalNutritionHint,
        )
    }

    @Test
    fun `식사 전체의 값이 일부만 적혀 있으면 적힌 항목만 돌려주고 음수는 버린다`() {
        givenPhotoServings(
            createPhotoServing(index = 1),
            total = mapOf("calory" to 650, "carbs" to null, "protein" to -3, "fat" to null),
        )

        val result = getPhotoServingNutritions(PhotoServingNutritionInput("불고기"))

        assertEquals(NutritionHint(calory = BigDecimal("650.00")), result.totalNutritionHint)
    }

    @Test
    fun `식사 전체의 값이 적혀 있지 않으면 null이다`() {
        givenPhotoServings(
            createPhotoServing(index = 1),
            total = mapOf("calory" to null, "carbs" to null, "protein" to null, "fat" to null),
        )

        assertNull(getPhotoServingNutritions(PhotoServingNutritionInput("불고기")).totalNutritionHint)
    }

    @Test
    fun `결과에 요청한 음식이 빠져 있으면 INVALID_RESPONSE다`() {
        givenPhotoServings(createPhotoServing(index = 1))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            getPhotoServingNutritions(PhotoServingNutritionInput("불고기"), PhotoServingNutritionInput("잡곡밥"))
        }
    }

    @Test
    fun `목록에 없는 아이콘을 고르면 기본 아이콘으로 바꾼다`() {
        givenPhotoServings(createPhotoServing(index = 1, iconId = 999L))

        val result = getPhotoServingNutritions(PhotoServingNutritionInput("불고기"))

        assertEquals(Icon.DEFAULT_ID, result.servingNutritions.single().iconId)
    }

    @Test
    fun `사진을 함께 보낸 조회에서 음식이 아닌 이름은 1인분을 모두 0으로, 인분을 1로 돌려준다`() {
        givenPhotoServings(
            createPhotoServing(index = 1, portion = "2", calory = 120, iconId = 12L) + mapOf("isFood" to false),
            createPhotoServing(index = 2, portion = "1.5", calory = 300, iconId = 5L) + mapOf("isFood" to true),
        )

        val result = getPhotoServingNutritions(PhotoServingNutritionInput("의자"), PhotoServingNutritionInput("잡곡밥"))

        val notFood = result.servingNutritions.first()
        val zero = BigDecimal("0.00")
        assertEquals(ServingNutrition(zero, zero, zero, zero), notFood.servingNutrition)
        assertEquals(Icon.DEFAULT_ID, notFood.iconId)
        assertEquals(MealFood.DEFAULT_PORTION, notFood.portion)
        assertFalse(notFood.isFood)
        assertEquals(BigDecimal("1.5"), result.servingNutritions.last().portion)
        assertTrue(result.servingNutritions.last().isFood)
    }

    @Test
    fun `사진을 함께 보낸 조회의 응답 스키마도 음식마다 isFood를 반드시 받는다`() {
        val responseSchema = slot<Map<String, Any>>()
        every { geminiClient.createContent(any(), any(), capture(responseSchema), any(), any()) } returns
            objectMapper.writeValueAsString(
                mapOf("foods" to listOf(createPhotoServing(index = 1)), "total" to emptyMap<String, Any>()),
            )

        getPhotoServingNutritions(PhotoServingNutritionInput("불고기"))

        val foods = (responseSchema.captured["properties"] as Map<*, *>)["foods"] as Map<*, *>
        val foodSchema = foods["items"] as Map<*, *>
        assertEquals(mapOf("type" to "BOOLEAN"), (foodSchema["properties"] as Map<*, *>)["isFood"])
        assertContains(foodSchema["required"] as List<*>, "isFood")
    }

    @Test
    fun `구할 음식이 없으면 Gemini를 부르지 않는다`() {
        val result = getPhotoServingNutritions()

        assertTrue(result.servingNutritions.isEmpty())
        assertNull(result.totalNutritionHint)
        verify(exactly = 0) { geminiClient.createContent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `사진과 사진 영양 모델과 LOW thinking으로 Gemini를 부른다`() {
        val model = slot<String>()
        val parts = slot<List<Map<String, Any>>>()
        val thinkingLevel = slot<String>()
        every {
            geminiClient.createContent(capture(model), capture(parts), any(), any(), capture(thinkingLevel))
        } returns
            objectMapper.writeValueAsString(
                mapOf(
                    "foods" to listOf(createPhotoServing(index = 1)),
                    "total" to emptyMap<String, Any>(),
                ),
            )

        getPhotoServingNutritions(PhotoServingNutritionInput("불고기"))

        assertEquals("gemini-photo-nutrition-test", model.captured)
        assertEquals(createGeminiImagePart(imageBytes, contentType), parts.captured.first())
        assertEquals(GeminiClient.LOW_THINKING_LEVEL, thinkingLevel.captured)
    }

    @Test
    fun `사진에 일부 값이 적혀 있던 음식은 그 값을 목록에 함께 적어 보낸다`() {
        val parts = slot<List<Map<String, Any>>>()
        every { geminiClient.createContent(any(), capture(parts), any(), any(), any()) } returns
            objectMapper.writeValueAsString(
                mapOf(
                    "foods" to listOf(createPhotoServing(index = 1), createPhotoServing(index = 2)),
                    "total" to emptyMap<String, Any>(),
                ),
            )

        getPhotoServingNutritions(
            PhotoServingNutritionInput("제육덮밥", NutritionHint(calory = BigDecimal("780.00"))),
            PhotoServingNutritionInput("된장\n찌개"),
        )

        val prompt = parts.captured.last().getValue("text") as String
        assertContains(prompt, "1. 제육덮밥 (사진에 적힌 값: 칼로리 780.00kcal)\n2. 된장 찌개")
    }
}

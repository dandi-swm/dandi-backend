package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.meal.dto.IconSummaryResult
import com.dandi.nyummy.meal.dto.ServingNutrition
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
}

package com.dandi.nyummy.infra.ai.recognition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.createGeminiImagePart
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.enum.MealInputCategory
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * 모델이 낸 인식 결과를 저장할 수 있는 모양으로 맞추는지, 음식이 아닌 사진과 해석할 수 없는 결과를 실패로 나누는지 다룬다.
 * Gemini 호출은 mockk로 흉내 낸다. 호출 자체의 실패 분류는 GeminiClientTest가 다룬다.
 */
class GeminiMealRecognitionClientTest {

    private val aiProperties = AiProperties(
        apiKey = "test-api-key",
        model = "gemini-test",
        recognitionModel = "gemini-recognition-test",
        servingNutritionModel = "gemini-serving-nutrition-test",
        catCommentModel = "gemini-cat-comment-test",
        baseUrl = "https://gemini.test",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(1),
        maxConnections = 1,
        connectionRequestTimeout = Duration.ofSeconds(1),
    )
    private val imageBytes = byteArrayOf(1, 2, 3)
    private val contentType = "image/jpeg"

    private val geminiClient = mockk<GeminiClient>()
    private val objectMapper = jacksonObjectMapper()
    private val client = GeminiMealRecognitionClient(geminiClient, aiProperties, objectMapper)

    private fun assertAnalysisFailure(expected: AnalysisFailureCategory, block: () -> Unit) {
        val exception = assertFailsWith<AnalysisFailureException>(block = block)
        assertEquals(expected, exception.category)
    }

    private fun givenResultJson(resultJson: String) {
        every { geminiClient.createContent(any(), any(), any(), any()) } returns resultJson
    }

    /** 모델이 낸 인식 결과. 인분은 스키마가 문자열 목록에서 고르게 하므로 문자열로 온다. */
    private fun givenRecognition(
        inputCategory: String = "FOOD_PHOTO",
        name: String = "불고기 백반",
        foods: List<Pair<String, String>> = listOf("불고기" to "1.5", "잡곡밥" to "1"),
    ) {
        givenResultJson(
            objectMapper.writeValueAsString(
                mapOf(
                    "inputCategory" to inputCategory,
                    "foods" to foods.map { (foodName, portion) -> mapOf("name" to foodName, "portion" to portion) },
                    "name" to name,
                ),
            ),
        )
    }

    private fun recognizeMeal(language: Language = Language.KO, timeout: Duration? = null) =
        client.recognizeMeal(imageBytes, contentType, language, timeout)

    @Test
    fun `인식 결과를 입력 유형과 대표 이름과 음식 줄로 바꾼다`() {
        givenRecognition()

        val result = recognizeMeal()

        assertEquals(MealInputCategory.FOOD_PHOTO, result.inputCategory)
        assertEquals("불고기 백반", result.name)
        assertEquals(
            listOf(
                MealFoodRecognitionResult(name = "불고기", portion = BigDecimal("1.5")),
                MealFoodRecognitionResult(name = "잡곡밥", portion = BigDecimal("1.0")),
            ),
            result.foods,
        )
    }

    @Test
    fun `인식에 쓴 모델과 프롬프트 버전을 결과에 싣는다`() {
        givenRecognition()

        val result = recognizeMeal()

        assertEquals("gemini-recognition-test", result.model)
        assertEquals("v1", result.promptVersion)
    }

    @Test
    fun `인분이 0_5 단위가 아니면 가장 가까운 0_5 단위로 맞춘다`() {
        givenRecognition(foods = listOf("불고기" to "0.8", "잡곡밥" to "1.2", "콩나물국" to "1.25"))

        val result = recognizeMeal()

        assertEquals(listOf(BigDecimal("1.0"), BigDecimal("1.0"), BigDecimal("1.5")), result.foods.map { it.portion })
    }

    @Test
    fun `인분이 범위를 벗어나면 0_5에서 5 사이로 넣는다`() {
        givenRecognition(foods = listOf("불고기" to "0.1", "잡곡밥" to "7"))

        val result = recognizeMeal()

        assertEquals(listOf(BigDecimal("0.5"), BigDecimal("5.0")), result.foods.map { it.portion })
    }

    @Test
    fun `인분이 숫자가 아니면 INVALID_RESPONSE다`() {
        givenRecognition(foods = listOf("불고기" to "많이"))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) { recognizeMeal() }
    }

    @Test
    fun `음식 이름이 30자를 넘으면 30자에서 자른다`() {
        givenRecognition(foods = listOf("가".repeat(31) to "1"))

        val result = recognizeMeal()

        assertEquals("가".repeat(30), result.foods.single().name)
    }

    @Test
    fun `음식 줄이 10개를 넘으면 앞의 10개만 받는다`() {
        givenRecognition(foods = (1..12).map { "음식$it" to "1" })

        val result = recognizeMeal()

        assertEquals((1..10).map { "음식$it" }, result.foods.map { it.name })
    }

    @Test
    fun `이름이 빈 음식 줄은 뺀다`() {
        givenRecognition(foods = listOf("불고기" to "1", "  " to "1", "잡곡밥" to "1"))

        val result = recognizeMeal()

        assertEquals(listOf("불고기", "잡곡밥"), result.foods.map { it.name })
    }

    @Test
    fun `대표 이름이 20자를 넘으면 20자에서 자른다`() {
        givenRecognition(name = "가".repeat(21))

        val result = recognizeMeal()

        assertEquals("가".repeat(20), result.name)
    }

    @Test
    fun `대표 이름이 비면 대표 음식의 이름을 쓴다`() {
        givenRecognition(name = " ")

        val result = recognizeMeal()

        assertEquals("불고기", result.name)
    }

    @Test
    fun `음식이 아닌 사진이면 NOT_FOOD다`() {
        givenRecognition(inputCategory = "NOT_FOOD", name = "", foods = emptyList())

        assertAnalysisFailure(AnalysisFailureCategory.NOT_FOOD) { recognizeMeal() }
    }

    @Test
    fun `음식 줄이 하나도 없으면 입력 유형과 상관없이 NOT_FOOD다`() {
        givenRecognition(inputCategory = "FOOD_PHOTO", foods = listOf(" " to "1"))

        assertAnalysisFailure(AnalysisFailureCategory.NOT_FOOD) { recognizeMeal() }
    }

    @Test
    fun `결과가 JSON이 아니면 INVALID_RESPONSE다`() {
        givenResultJson("JSON이 아님")

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) { recognizeMeal() }
    }

    @Test
    fun `모르는 입력 유형이면 INVALID_RESPONSE다`() {
        givenRecognition(inputCategory = "DRAWING")

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) { recognizeMeal() }
    }

    @Test
    fun `Gemini 호출의 실패는 그대로 올린다`() {
        every { geminiClient.createContent(any(), any(), any(), any()) } throws
            AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "Gemini 요청 한도를 넘었습니다.")

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { recognizeMeal() }
    }

    @Test
    fun `사진과 인식 모델과 제한 시간을 Gemini 호출에 넘긴다`() {
        val model = slot<String>()
        val parts = slot<List<Map<String, Any>>>()
        val timeout = slot<Duration>()
        every { geminiClient.createContent(capture(model), capture(parts), any(), capture(timeout)) } returns
            createResultJson()

        recognizeMeal(timeout = Duration.ofSeconds(3))

        assertEquals("gemini-recognition-test", model.captured)
        assertEquals(createGeminiImagePart(imageBytes, contentType), parts.captured.first())
        assertEquals(Duration.ofSeconds(3), timeout.captured)
    }

    @Test
    fun `한국어 요청에는 출력 언어 지시를 덧붙이지 않는다`() {
        assertFalse(capturePrompt(Language.KO).contains("[출력 언어]"))
    }

    @Test
    fun `일본어와 영어 요청에는 그 언어로 쓰라는 지시를 덧붙인다`() {
        assertContains(capturePrompt(Language.JA), "모두 일본어로 써")
        assertContains(capturePrompt(Language.EN), "모두 영어로 써")
    }

    private fun createResultJson(): String = objectMapper.writeValueAsString(
        mapOf(
            "inputCategory" to "FOOD_PHOTO",
            "foods" to listOf(mapOf("name" to "불고기", "portion" to "1")),
            "name" to "불고기 백반",
        ),
    )

    /** [language]로 인식을 요청했을 때 Gemini에 넘긴 프롬프트. */
    private fun capturePrompt(language: Language): String {
        val parts = slot<List<Map<String, Any>>>()
        every { geminiClient.createContent(any(), capture(parts), any(), any()) } returns createResultJson()

        recognizeMeal(language)

        return parts.captured.last().getValue("text") as String
    }
}

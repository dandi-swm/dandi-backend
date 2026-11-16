package com.dandi.nyummy.infra.ai.recognition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.createGeminiImagePart
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.NutritionHint
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
import kotlin.test.assertNull

/**
 * 모델이 낸 인식 결과를 저장할 수 있는 모양으로 맞추는지, 음식이 아닌 사진과 해석할 수 없는 결과를 실패로 나누는지 다룬다.
 * Gemini 호출은 mockk로 흉내 낸다. 호출 자체의 실패 분류는 GeminiClientTest가 다룬다.
 */
class GeminiMealRecognitionClientTest {

    private val aiProperties = AiProperties(
        apiKey = "test-api-key",
        model = "gemini-test",
        recognitionModel = "gemini-recognition-test",
        namedNutritionModel = "gemini-named-nutrition-test",
        photoNutritionModel = "gemini-photo-nutrition-test",
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

    /**
     * 모델이 낸 인식 결과.
     *
     * @param foods 음식 이름과 그 줄의 힌트. 힌트는 적혀 있던 항목만 담고, 적힌 것이 없으면 빈 맵이다
     */
    private fun givenRecognition(
        inputCategory: String = "FOOD_PHOTO",
        name: String = "불고기 백반",
        foods: List<Pair<String, Map<String, Any?>>> = listOf("불고기" to emptyMap(), "잡곡밥" to emptyMap()),
    ) {
        givenResultJson(
            objectMapper.writeValueAsString(
                mapOf(
                    "inputCategory" to inputCategory,
                    "foods" to foods.map { (foodName, nutritionHint) -> mapOf("name" to foodName) + nutritionHint },
                    "name" to name,
                ),
            ),
        )
    }

    private fun givenFoodNames(vararg names: String) = givenRecognition(foods = names.map { it to emptyMap() })

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
                MealFoodRecognitionResult(name = "불고기", nutritionHint = null),
                MealFoodRecognitionResult(name = "잡곡밥", nutritionHint = null),
            ),
            result.foods,
        )
    }

    @Test
    fun `인식에 쓴 모델과 프롬프트 버전을 결과에 싣는다`() {
        givenRecognition()

        val result = recognizeMeal()

        assertEquals("gemini-recognition-test", result.model)
        assertEquals("v2", result.promptVersion)
    }

    @Test
    fun `사진에 적혀 있던 값은 그 줄의 힌트로 싣고 소수 둘째 자리로 맞춘다`() {
        givenRecognition(
            foods = listOf(
                "닭가슴살 샐러드" to mapOf("calory" to 320, "carbs" to 12.5, "protein" to 35, "fat" to 9.125),
                "아메리카노" to emptyMap(),
            ),
        )

        val result = recognizeMeal()

        assertEquals(
            NutritionHint(BigDecimal("320.00"), BigDecimal("12.50"), BigDecimal("35.00"), BigDecimal("9.13")),
            result.foods[0].nutritionHint,
        )
        assertNull(result.foods[1].nutritionHint)
    }

    @Test
    fun `일부만 적혀 있던 줄은 적힌 항목만 힌트에 싣는다`() {
        // 메뉴판처럼 칼로리만 적힌 경우. 모델은 없는 항목을 null로 보내거나 아예 빼고 보낸다.
        givenRecognition(
            foods = listOf(
                "제육덮밥" to mapOf("calory" to 780, "carbs" to null, "protein" to null, "fat" to null),
                "된장찌개" to mapOf("calory" to 150),
            ),
        )

        val result = recognizeMeal()

        assertEquals(NutritionHint(calory = BigDecimal("780.00")), result.foods[0].nutritionHint)
        assertEquals(NutritionHint(calory = BigDecimal("150.00")), result.foods[1].nutritionHint)
    }

    @Test
    fun `음수이거나 저장할 수 없게 큰 힌트 값은 그 항목만 버리고 인식은 계속한다`() {
        givenRecognition(
            foods = listOf(
                "불고기" to mapOf("calory" to 350, "carbs" to -5, "protein" to 100000, "fat" to 20),
                "잡곡밥" to mapOf("calory" to -1),
            ),
        )

        val result = recognizeMeal()

        assertEquals(
            NutritionHint(calory = BigDecimal("350.00"), fat = BigDecimal("20.00")),
            result.foods[0].nutritionHint,
        )
        // 남은 항목이 하나도 없으면 힌트가 없는 줄이다.
        assertNull(result.foods[1].nutritionHint)
    }

    @Test
    fun `응답 스키마는 인분을 받지 않고 힌트 넷을 비어 있을 수 있는 숫자로 받는다`() {
        val responseSchema = captureResponseSchema()

        val foods = (responseSchema["properties"] as Map<*, *>)["foods"] as Map<*, *>
        val foodSchema = foods["items"] as Map<*, *>
        val properties = foodSchema["properties"] as Map<*, *>
        assertEquals(setOf("name", "calory", "carbs", "protein", "fat"), properties.keys)
        assertEquals(mapOf("type" to "NUMBER", "nullable" to true), properties["calory"])
        assertEquals(listOf("name"), foodSchema["required"])
    }

    @Test
    fun `응답 스키마의 입력 유형에는 영양성분표가 없다`() {
        val responseSchema = captureResponseSchema()

        val inputCategory = (responseSchema["properties"] as Map<*, *>)["inputCategory"] as Map<*, *>
        assertEquals(listOf("FOOD_PHOTO", "ILLUSTRATION", "PACKAGE", "TEXT_RECORD", "NOT_FOOD"), inputCategory["enum"])
    }

    @Test
    fun `음식 이름이 30자를 넘으면 30자에서 자른다`() {
        givenFoodNames("가".repeat(31))

        val result = recognizeMeal()

        assertEquals("가".repeat(30), result.foods.single().name)
    }

    @Test
    fun `음식 줄이 10개를 넘으면 앞의 10개만 받는다`() {
        givenFoodNames(*(1..12).map { "음식$it" }.toTypedArray())

        val result = recognizeMeal()

        assertEquals((1..10).map { "음식$it" }, result.foods.map { it.name })
    }

    @Test
    fun `이름이 빈 음식 줄은 뺀다`() {
        givenFoodNames("불고기", "  ", "잡곡밥")

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
        givenRecognition(inputCategory = "FOOD_PHOTO", foods = listOf(" " to emptyMap()))

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
    fun `식사 전체의 값으로 적힌 숫자는 어느 음식에도 적지 말고, 음식이 하나면 그 음식의 값으로 적으라고 지시한다`() {
        val prompt = capturePrompt(Language.KO)

        assertContains(prompt, "식사 전체의 값은 어느 음식에도 적지 말고")
        assertContains(prompt, "음식이 하나뿐이면 적혀 있는 값이 그 음식의 값이니 그대로 적어")
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
            "foods" to listOf(mapOf("name" to "불고기")),
            "name" to "불고기 백반",
        ),
    )

    /** 인식을 요청했을 때 Gemini에 넘긴 응답 스키마. */
    private fun captureResponseSchema(): Map<String, Any> {
        val responseSchema = slot<Map<String, Any>>()
        every { geminiClient.createContent(any(), any(), capture(responseSchema), any()) } returns createResultJson()

        recognizeMeal()

        return responseSchema.captured
    }

    /** [language]로 인식을 요청했을 때 Gemini에 넘긴 프롬프트. */
    private fun capturePrompt(language: Language): String {
        val parts = slot<List<Map<String, Any>>>()
        every { geminiClient.createContent(any(), capture(parts), any(), any()) } returns createResultJson()

        recognizeMeal(language)

        return parts.captured.last().getValue("text") as String
    }
}

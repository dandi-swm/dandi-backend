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
import com.dandi.nyummy.meal.enum.NetContentUnit
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
        assertEquals("v4", result.promptVersion)
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
    fun `응답 스키마는 인분을 받지 않고 힌트 넷과 용량을 비어 있을 수 있는 값으로 받는다`() {
        val responseSchema = captureResponseSchema()

        val foods = (responseSchema["properties"] as Map<*, *>)["foods"] as Map<*, *>
        val foodSchema = foods["items"] as Map<*, *>
        val properties = foodSchema["properties"] as Map<*, *>
        assertEquals(
            setOf("name", "calory", "carbs", "protein", "fat", "hasPackageName", "netContent", "netContentUnit"),
            properties.keys,
        )
        assertEquals(mapOf("type" to "NUMBER", "nullable" to true), properties["calory"])
        assertEquals(mapOf("type" to "NUMBER", "nullable" to true), properties["netContent"])
        assertEquals(
            mapOf("type" to "STRING", "enum" to listOf("G", "ML", "KG", "L"), "nullable" to true),
            properties["netContentUnit"],
        )
        assertEquals(mapOf("type" to "BOOLEAN"), properties["hasPackageName"])
        assertEquals(listOf("name", "hasPackageName"), foodSchema["required"])
    }

    @Test
    fun `응답 스키마는 음식 줄의 필드를 이름, 제품명인지, 용량, 영양 값 순서로 쓰게 한다`() {
        // 순서를 정해 두지 않으면 모델이 용량을 건너뛴 뒤 마지막 숫자를 끝없이 이어 써서 결과를 해석하지 못했다.
        val responseSchema = captureResponseSchema()

        val foods = (responseSchema["properties"] as Map<*, *>)["foods"] as Map<*, *>
        val foodSchema = foods["items"] as Map<*, *>
        assertEquals(
            listOf("name", "hasPackageName", "netContent", "netContentUnit", "calory", "carbs", "protein", "fat"),
            foodSchema["propertyOrdering"],
        )
    }

    // 이름이 제품명인지

    @Test
    fun `제품 사진의 줄은 이름이 포장에서 읽은 제품명인지를 모델이 답한 대로 싣는다`() {
        givenRecognition(
            inputCategory = "PACKAGE",
            foods = listOf(
                "셀렉스 프로핏 딸기초코" to mapOf("hasPackageName" to true),
                "완전단백질 20g" to mapOf("hasPackageName" to false),
                // 모델이 빼고 보내면 제품명이 아닌 것으로 본다.
                "단백질 음료" to emptyMap(),
            ),
        )

        val result = recognizeMeal()

        assertEquals(listOf(true, false, false), result.foods.map { it.hasPackageName })
    }

    @Test
    fun `제품이 아닌 유형의 사진은 모델이 제품명이라고 답해도 제품명으로 보지 않는다`() {
        listOf("FOOD_PHOTO", "TEXT_RECORD", "ILLUSTRATION").forEach { inputCategory ->
            givenRecognition(inputCategory = inputCategory, foods = listOf("콜라" to mapOf("hasPackageName" to true)))

            val result = recognizeMeal()

            assertFalse(result.foods.single().hasPackageName, inputCategory)
        }
    }

    @Test
    fun `영양 성분 문구와 음식 종류는 제품 이름이 아니라고, 영양 값은 그와 상관없이 옮겨 적으라고 지시한다`() {
        val prompt = capturePrompt(Language.KO)

        assertContains(prompt, "hasPackageName은 name이 포장에 적힌 제품 이름(브랜드나 제품명)이면 true야")
        assertContains(prompt, "음식 종류(예: \"컵라면\")는 제품 이름이 아니야")
        assertContains(prompt, "이 판단과 상관없이 사진에 적힌 영양 값은 위에서 정한 대로 그대로 옮겨 적어")
    }

    // 용량

    @Test
    fun `제품 사진에 적혀 있던 용량을 그 줄에 싣고 소수 첫째 자리로 맞춘다`() {
        givenRecognition(
            inputCategory = "PACKAGE",
            foods = listOf(
                "셀렉스 프로핏" to mapOf("netContent" to 250, "netContentUnit" to "ML"),
                "꼬북칩" to mapOf("netContent" to 16.55, "netContentUnit" to "G"),
            ),
        )

        val result = recognizeMeal()

        assertEquals(BigDecimal("250.0"), result.foods[0].netContent)
        assertEquals(NetContentUnit.ML, result.foods[0].netContentUnit)
        assertEquals(BigDecimal("16.6"), result.foods[1].netContent)
        assertEquals(NetContentUnit.G, result.foods[1].netContentUnit)
    }

    @Test
    fun `kg과 L로 적힌 용량은 g과 ml로 바꾼다`() {
        givenRecognition(
            inputCategory = "PACKAGE",
            foods = listOf(
                "쌀" to mapOf("netContent" to 2.27, "netContentUnit" to "KG"),
                "코카콜라 제로" to mapOf("netContent" to 1.5, "netContentUnit" to "L"),
            ),
        )

        val result = recognizeMeal()

        assertEquals(BigDecimal("2270.0"), result.foods[0].netContent)
        assertEquals(NetContentUnit.G, result.foods[0].netContentUnit)
        assertEquals(BigDecimal("1500.0"), result.foods[1].netContent)
        assertEquals(NetContentUnit.ML, result.foods[1].netContentUnit)
    }

    @Test
    fun `단위는 대소문자와 앞뒤 공백을 가리지 않고 받는다`() {
        givenRecognition(
            inputCategory = "PACKAGE",
            foods = listOf("두유" to mapOf("netContent" to 190, "netContentUnit" to " ml ")),
        )

        val result = recognizeMeal()

        assertEquals(BigDecimal("190.0"), result.foods.single().netContent)
        assertEquals(NetContentUnit.ML, result.foods.single().netContentUnit)
    }

    @Test
    fun `제품이 아닌 유형의 사진은 용량이 와도 받지 않는다`() {
        listOf("FOOD_PHOTO", "TEXT_RECORD", "ILLUSTRATION").forEach { inputCategory ->
            givenRecognition(
                inputCategory = inputCategory,
                foods = listOf("콜라" to mapOf("netContent" to 500, "netContentUnit" to "ML")),
            )

            val result = recognizeMeal()

            assertNull(result.foods.single().netContent, inputCategory)
            assertNull(result.foods.single().netContentUnit, inputCategory)
        }
    }

    @Test
    fun `용량이 적혀 있지 않은 제품은 용량이 없다`() {
        givenRecognition(
            inputCategory = "PACKAGE",
            foods = listOf(
                "신라면" to emptyMap(),
                "삼각김밥" to mapOf("netContent" to null, "netContentUnit" to null),
            ),
        )

        val result = recognizeMeal()

        assertNull(result.foods[0].netContent)
        assertNull(result.foods[0].netContentUnit)
        assertNull(result.foods[1].netContent)
        assertNull(result.foods[1].netContentUnit)
    }

    @Test
    fun `숫자나 단위 하나만 왔거나 모르는 단위이면 용량을 버리고 인식은 계속한다`() {
        givenRecognition(
            inputCategory = "PACKAGE",
            foods = listOf(
                "두유" to mapOf("netContent" to 190),
                "우유" to mapOf("netContentUnit" to "ML"),
                "비타민" to mapOf("netContent" to 30, "netContentUnit" to "정"),
            ),
        )

        val result = recognizeMeal()

        assertEquals(listOf("두유", "우유", "비타민"), result.foods.map { it.name })
        assertEquals(listOf(null, null, null), result.foods.map { it.netContent })
        assertEquals(listOf(null, null, null), result.foods.map { it.netContentUnit })
    }

    @Test
    fun `0 이하이거나 저장할 수 없게 큰 용량은 버리고 인식은 계속한다`() {
        givenRecognition(
            inputCategory = "PACKAGE",
            foods = listOf(
                "두유" to mapOf("netContent" to 0, "netContentUnit" to "ML"),
                "우유" to mapOf("netContent" to -200, "netContentUnit" to "ML"),
                // 반올림하면 0.0이 되는 값
                "소금" to mapOf("netContent" to 0.04, "netContentUnit" to "G"),
                // 1000kg은 g으로 바꾸면 컬럼에 담을 수 없다.
                "쌀" to mapOf("netContent" to 1000, "netContentUnit" to "KG"),
                "설탕" to mapOf("netContent" to 999.9999, "netContentUnit" to "KG"),
            ),
        )

        val result = recognizeMeal()

        assertEquals(5, result.foods.size)
        assertEquals(listOf(null, null, null, null, BigDecimal("999999.9")), result.foods.map { it.netContent })
        assertEquals(listOf(null, null, null, null, NetContentUnit.G), result.foods.map { it.netContentUnit })
    }

    @Test
    fun `용량은 제품일 때만, 묶음 상품은 낱개의 용량을, 짐작하지 말고 적으라고 지시한다`() {
        val prompt = capturePrompt(Language.KO)

        assertContains(prompt, "netContent·netContentUnit은 PACKAGE일 때만 적어")
        assertContains(prompt, "낱개 하나의 용량을 적어")
        assertContains(prompt, "둘 다 null로 둬")
        // 이름에 용량이 붙으면 같은 제품이 다른 이름으로 저장된다.
        assertContains(prompt, "용량은 여기에만 적고 name에는 넣지 마")
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
    fun `대표 이름은 브랜드와 제품명을 뺀 음식 종류로 적고 음식 줄의 이름은 구체적으로 두라고 지시한다`() {
        val prompt = capturePrompt(Language.KO)

        assertContains(prompt, "무엇을 먹었는지 알 수 있는 음식 종류로 적어. 브랜드, 제품명, 맛, 용량은 빼.")
        assertContains(prompt, "foods의 name은 여기에 맞추지 말고")
    }

    @Test
    fun `한국어 요청에는 한국어로 쓴 프롬프트를 보내고 이름을 한국어로 쓰게 한다`() {
        val prompt = capturePrompt(Language.KO)

        assertContains(prompt, "그 밖의 이름(hasPackageName이 false인 이름)은 반드시 한국어로 적어.")
        assertContains(prompt, "식사 이름은 항상 한국어로 적어.")
    }

    @Test
    fun `일본어 요청에는 일본어로 쓴 프롬프트를 보내고 이름을 일본어로 쓰게 한다`() {
        val prompt = capturePrompt(Language.JA)

        assertContains(prompt, "それ以外の名前(hasPackageNameがfalseの名前)は必ず日本語で書いて。")
        assertContains(prompt, "食事の名前は必ず日本語で書いて。")
        // 한국어 지시가 남아 있으면 결과가 한국어로 끌려간다.
        assertFalse(prompt.contains("이 사진에서"))
    }

    @Test
    fun `영어 요청에는 영어로 쓴 프롬프트를 보내고 식사 이름을 20글자 안으로 쓰게 한다`() {
        val prompt = capturePrompt(Language.EN)

        assertContains(prompt, "Every other name (a name for which hasPackageName is false) must be in English.")
        assertContains(prompt, "Write a name for this meal within 20 characters, counting spaces.")
        assertFalse(prompt.contains("이 사진에서"))
    }

    @Test
    fun `어느 언어의 프롬프트든 포장에 적힌 제품 이름만 다른 언어여도 적힌 그대로 쓰게 한다`() {
        assertContains(
            capturePrompt(Language.KO),
            "포장에 적힌 브랜드나 제품명(hasPackageName이 true인 이름)만 다른 언어여도 옮기지 말고 적힌 그대로 적어.",
        )
        assertContains(
            capturePrompt(Language.JA),
            "パッケージに書かれたブランドや製品名(hasPackageNameがtrueの名前)だけは、他の言語でも直さず書かれたとおりに書いて。",
        )
        assertContains(
            capturePrompt(Language.EN),
            "Only a brand or product name written on the package (a name for which hasPackageName is true) " +
                "is written exactly as it appears, even in another language.",
        )
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

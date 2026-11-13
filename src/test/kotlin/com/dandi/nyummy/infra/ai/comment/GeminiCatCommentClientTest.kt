package com.dandi.nyummy.infra.ai.comment

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.CaloryShareBalanceResult
import com.dandi.nyummy.meal.dto.CatCommentInput
import com.dandi.nyummy.meal.dto.CatCommentMealFoodInput
import com.dandi.nyummy.meal.dto.CatCommentMealInput
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.enum.MacroNutrient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 코멘트 재료가 프롬프트에 어떻게 들어가는지와, 모델이 낸 코멘트를 저장할 수 있는 모양으로 맞추는지 다룬다.
 * Gemini 호출은 mockk로 흉내 낸다.
 */
class GeminiCatCommentClientTest {

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

    private val geminiClient = mockk<GeminiClient>()
    private val objectMapper = jacksonObjectMapper()
    private val client = GeminiCatCommentClient(geminiClient, aiProperties, objectMapper)

    private fun createInput(
        earlierMeals: List<CatCommentMealInput> = emptyList(),
        caloryShareBalance: CaloryShareBalanceResult? = CaloryShareBalanceResult(emptyList(), emptyList()),
    ) = CatCommentInput(
        catName = "냥이",
        mealFoods = listOf(
            CatCommentMealFoodInput(
                "불고기",
                BigDecimal("1.5"),
                Nutrition(calory = 525, carbs = 23, protein = 38, fat = 30),
            ),
            CatCommentMealFoodInput(
                "잡곡\n밥",
                BigDecimal("1.0"),
                Nutrition(calory = 300, carbs = 65, protein = 6, fat = 1),
            ),
        ),
        earlierMeals = earlierMeals,
        current = Nutrition(calory = 1245, carbs = 140, protein = 58, fat = 48),
        target = Nutrition(calory = 2000, carbs = 250, protein = 100, fat = 70),
        caloryShareBalance = caloryShareBalance,
    )

    private fun givenCatComment(catComment: String) {
        every { geminiClient.createContent(any(), any(), any(), any()) } returns
            objectMapper.writeValueAsString(mapOf("catComment" to catComment))
    }

    /** [input]과 [language]로 코멘트를 만들 때 Gemini에 넘긴 프롬프트. */
    private fun capturePrompt(input: CatCommentInput = createInput(), language: Language = Language.KO): String {
        val parts = slot<List<Map<String, Any>>>()
        every { geminiClient.createContent(any(), capture(parts), any(), any()) } returns
            objectMapper.writeValueAsString(mapOf("catComment" to "든든하게 먹었다냥"))

        client.createCatComment(input, language)

        return parts.captured.single().getValue("text") as String
    }

    private fun assertAnalysisFailure(expected: AnalysisFailureCategory, block: () -> Unit) {
        val exception = assertFailsWith<AnalysisFailureException>(block = block)
        assertEquals(expected, exception.category)
    }

    @Test
    fun `모델이 쓴 코멘트를 앞뒤 공백 없이 돌려준다`() {
        givenCatComment("  든든하게 먹었다냥  ")

        assertEquals("든든하게 먹었다냥", client.createCatComment(createInput(), Language.KO))
    }

    @Test
    fun `받은 응답 대기 제한을 Gemini 호출에 그대로 넘긴다`() {
        givenCatComment("든든하게 먹었다냥")

        client.createCatComment(createInput(), Language.KO, Duration.ofSeconds(10))

        verify(exactly = 1) { geminiClient.createContent(any(), any(), any(), Duration.ofSeconds(10)) }
    }

    @Test
    fun `코멘트가 컬럼보다 길면 255자에서 자른다`() {
        givenCatComment("냥".repeat(300))

        assertEquals("냥".repeat(255), client.createCatComment(createInput(), Language.KO))
    }

    @Test
    fun `코멘트가 비어 있으면 INVALID_RESPONSE다`() {
        givenCatComment("  ")

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            client.createCatComment(createInput(), Language.KO)
        }
    }

    @Test
    fun `결과가 JSON이 아니면 INVALID_RESPONSE다`() {
        every { geminiClient.createContent(any(), any(), any(), any()) } returns "JSON이 아님"

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            client.createCatComment(createInput(), Language.KO)
        }
    }

    @Test
    fun `프롬프트에 고양이 이름과 음식 줄의 인분과 영양을 넣는다`() {
        val prompt = capturePrompt()

        assertContains(prompt, """고양이 "냥이"야""")
        assertContains(prompt, "- 불고기 1.5인분 (525kcal, 탄 23g, 단 38g, 지 30g)")
        // 이름의 줄바꿈은 목록 모양을 깨지 않게 한 줄로 편다.
        assertContains(prompt, "- 잡곡 밥 1인분 (300kcal, 탄 65g, 단 6g, 지 1g)")
    }

    @Test
    fun `프롬프트에 오늘 먹은 양과 하루 목표를 넣는다`() {
        val prompt = capturePrompt()

        assertContains(prompt, "- 칼로리: 1245kcal / 목표 2000kcal")
        assertContains(prompt, "- 탄수화물: 140g / 목표 250g")
        assertContains(prompt, "- 단백질: 58g / 목표 100g")
        assertContains(prompt, "- 지방: 48g / 목표 70g")
    }

    @Test
    fun `앞선 식사가 있으면 시각과 이름과 영양을 넣고 없으면 첫 식사라고 쓴다`() {
        val earlierMeal = CatCommentMealInput(
            mealAt = LocalTime.of(8, 10),
            name = "토스트와 우유",
            nutrition = Nutrition(calory = 420, carbs = 52, protein = 14, fat = 17),
        )

        assertContains(
            capturePrompt(createInput(earlierMeals = listOf(earlierMeal))),
            "- 08:10 토스트와 우유 (420kcal, 탄 52g, 단 14g, 지 17g)",
        )
        assertContains(capturePrompt(createInput(earlierMeals = emptyList())), "오늘 첫 식사야.")
    }

    @Test
    fun `서버가 판정한 많은 영양소와 부족한 영양소를 넣는다`() {
        val balance = CaloryShareBalanceResult(
            excessNutrients = listOf(MacroNutrient.CARBS),
            lackingNutrients = listOf(MacroNutrient.PROTEIN, MacroNutrient.FAT),
        )

        val prompt = capturePrompt(createInput(caloryShareBalance = balance))

        assertContains(prompt, "- 많은 영양소: 탄수화물")
        assertContains(prompt, "- 부족한 영양소: 단백질·지방")
    }

    @Test
    fun `많은 것도 부족한 것도 없으면 균형을 이뤘다고 쓴다`() {
        val prompt = capturePrompt(createInput(caloryShareBalance = CaloryShareBalanceResult(emptyList(), emptyList())))

        assertContains(prompt, "목표 비율에 가깝게 균형을 이뤘어.")
        assertFalse(prompt.contains("- 많은 영양소"))
    }

    @Test
    fun `탄단지 기록이 없으면 영양 균형을 언급하지 말라고 쓴다`() {
        assertContains(capturePrompt(createInput(caloryShareBalance = null)), "영양 균형은 언급하지 마.")
    }

    @Test
    fun `한국어 요청에는 출력 언어 지시를 덧붙이지 않고 한국 음식 종류에서 추천하게 한다`() {
        val prompt = capturePrompt(language = Language.KO)

        assertFalse(prompt.contains("[출력 언어]"))
        assertTrue(listOf("한식", "중식", "일식", "양식").any { prompt.contains("이번 추천은 $it 메뉴 중에서 골라.") })
    }

    @Test
    fun `일본어와 영어 요청에는 그 언어로 쓰라는 지시를 덧붙이고 추천 음식 종류를 바꾼다`() {
        val japanesePrompt = capturePrompt(language = Language.JA)
        val englishPrompt = capturePrompt(language = Language.EN)

        assertContains(japanesePrompt, "일본어 고양이 말투")
        assertTrue(listOf("和食", "中華", "洋食", "韓国料理", "定食").any { japanesePrompt.contains("이번 추천은 $it 메뉴") })
        assertContains(englishPrompt, "고양이 말투의 영어")
        assertContains(englishPrompt, "160자 이내")
    }
}

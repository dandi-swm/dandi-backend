package com.dandi.nyummy.infra.ai.comment

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.createGeminiTextPart
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.CaloryShareBalanceResult
import com.dandi.nyummy.meal.dto.CatCommentInput
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MacroNutrient
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * 고양이 코멘트를 Gemini로 쓴다.
 *
 * 많은·부족한 영양소는 서버가 정해서 넘기고([CatCommentInput.caloryShareBalance]), 오늘은 그만 먹으라고 할지는
 * 먹은 양과 목표를 숫자로 주고 모델에 맡긴다. 숫자만 주면 모델은 거의 매번 단백질이 부족하다고 말한다.
 */
@Component
class GeminiCatCommentClient(
    private val geminiClient: GeminiClient,
    private val aiProperties: AiProperties,
    private val objectMapper: ObjectMapper,
) : CatCommentClient {

    companion object {

        // 분식은 떡볶이·라면·튀김 위주라 "더 건강한 메뉴" 기준과 맞지 않아 추천 후보에서 뺐다.
        private val KO_OUTPUT_LANGUAGE_PROMPT = OutputLanguagePrompt(listOf("한식", "중식", "일식", "양식"), instruction = null)

        // 한국어가 아닌 언어는 아래 한국어 지시를 그대로 두고, 출력 언어 지시를 맨 끝에 덧붙인다.
        // 메뉴 추천 후보는 그 언어를 쓰는 사람이 평소에 먹는 음식으로 바꾼다. 영어는 어느 나라 사용자인지 몰라 넓게 둔다.
        private val JA_OUTPUT_LANGUAGE_PROMPT = OutputLanguagePrompt(
            cuisines = listOf("和食", "中華", "洋食", "韓国料理", "定食"),
            instruction = """
        [출력 언어]
        catComment는 일본어 고양이 말투로 쓰고, 문장 끝을 "~にゃ"로 맺어. 한국어를 섞지 마.
        추천하는 메뉴 이름도 일본어로 써.
    """,
        )
        private val EN_OUTPUT_LANGUAGE_PROMPT = OutputLanguagePrompt(
            cuisines = listOf("Korean", "Southeast Asian", "East Asian", "Western", "Mediterranean"),
            // 영어는 같은 내용이 한국어보다 길어 글자 수를 늘린다.
            instruction = """
        [출력 언어]
        catComment는 장난스러운 고양이 말투의 영어로 쓰고 "meow"를 자연스럽게 넣어. 한국어를 섞지 마.
        위의 100자 대신 160자 이내로 써. 추천하는 메뉴 이름도 영어로 써.
    """,
        )

        private const val PROMPT =
            """
        너는 사용자의 식사를 지켜보는 고양이 "{catName}"야. 방금 기록한 식사에 고양이 말투로 100자 이내의 피드백을 JSON으로 써.

        방금 먹은 식사:
        {mealFoods}

        오늘 그 전에 먹은 식사:
        {earlierMeals}

        오늘 지금까지 먹은 양(방금 먹은 식사 포함)과 하루 목표:
        {intake}

        오늘 지금까지의 영양 균형(서버가 계산했으니 바꾸지 마):
        {balance}

        [쓰는 법]
        먼저 오늘 먹은 칼로리를 하루 목표와 비교해, 아래 1과 2 중 하나로만 써.
        1. 하루 목표에 거의 다 왔거나(대략 90% 이상) 넘었으면: 영양 균형이 어떻든 오늘은 그만 먹으라고 고양이답게 장난스럽게 말해(예: "그만 먹으라냥"). 넘었으면 더 호들갑스럽게. 나무라거나 죄책감을 주지 말고 웃기게 써. 다음 식사 메뉴는 추천하지 마.
        2. 아직 그보다 많이 남았으면: 위 영양 균형을 한 문장으로 짚고(균형이 좋으면 칭찬해), 다음 식사 메뉴를 하나 추천해. 부족한 영양소가 있으면 그것을 채울 메뉴로, 없으면 이 균형을 이어 갈 메뉴로 골라.
           - 배달 앱이나 식당 메뉴판에서 흔히 보는, 누구나 아는 메뉴 이름으로 써. 재료 이름이 아니라 메뉴 이름이어야 해.
           - 쉽게 사 먹거나 집에서 쉽게 만들 수 있는 메뉴로 골라.
           - 같은 조건이면 더 건강한 메뉴를 골라. 채소와 단백질이 함께 들어간 식사를 우선하고, 튀김·가공육·인스턴트 위주의 메뉴는 피해.
           - 그렇다고 닭가슴살·샐러드만 먹는 다이어트 식단은 그 영양소가 정말 부족할 때만 추천해.
           - 이번 추천은 {cuisine} 메뉴 중에서 골라.
           - 오늘 이미 먹은 음식은 추천하지 마.
        - 숫자는 한두 개만 자연스럽게 써도 되고 안 써도 돼.
        - 음식 종류 이름(한식·양식 등)은 말하지 마.
        - 몸·살·배 크기를 놀리지 마.
    """

        // 프롬프트의 지시가 한국어라 영양소 이름도 한국어로 넣는다.
        private val NUTRIENT_NAMES = mapOf(
            MacroNutrient.CARBS to "탄수화물",
            MacroNutrient.PROTEIN to "단백질",
            MacroNutrient.FAT to "지방",
        )

        private val MEAL_AT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

        private val RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf("catComment" to mapOf("type" to "STRING")),
            "required" to listOf("catComment"),
        )
    }

    override fun createCatComment(input: CatCommentInput, language: Language, timeout: Duration?): String {
        val resultJson = geminiClient.createContent(
            model = aiProperties.catCommentModel,
            parts = listOf(createGeminiTextPart(createPrompt(input, language))),
            responseSchema = RESPONSE_SCHEMA,
            timeout = timeout,
        )
        val catComment = convertCatCommentResponse(resultJson).catComment.trim()
        if (catComment.isEmpty()) {
            throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "고양이 코멘트가 비어 있습니다.")
        }

        return catComment.take(Meal.CAT_COMMENT_MAX_LENGTH)
    }

    /**
     * 코멘트 프롬프트. 다음 식사 추천이 한 메뉴로 몰리지 않게 음식 종류를 요청마다 무작위로 정하고,
     * 다른 언어는 메뉴 추천 후보를 바꾸고 출력 언어 지시를 맨 끝에 덧붙인다.
     */
    private fun createPrompt(input: CatCommentInput, language: Language): String {
        val outputLanguagePrompt = pickOutputLanguagePrompt(language)
        val prompt = PROMPT
            .replace("{catName}", convertSingleLine(input.catName))
            .replace("{mealFoods}", createMealFoodLines(input))
            .replace("{earlierMeals}", createEarlierMealLines(input))
            .replace("{intake}", createIntakeLines(input.current, input.target))
            .replace("{balance}", createBalanceLines(input.caloryShareBalance))
            .replace("{cuisine}", outputLanguagePrompt.cuisines.random())

        return outputLanguagePrompt.instruction?.let { "$prompt\n$it" } ?: prompt
    }

    // when으로 고르면 언어를 추가할 때 여기서 컴파일이 깨져 빠뜨리지 않는다
    private fun pickOutputLanguagePrompt(language: Language): OutputLanguagePrompt = when (language) {
        Language.KO -> KO_OUTPUT_LANGUAGE_PROMPT
        Language.JA -> JA_OUTPUT_LANGUAGE_PROMPT
        Language.EN -> EN_OUTPUT_LANGUAGE_PROMPT
    }

    private fun createMealFoodLines(input: CatCommentInput): String = input.mealFoods.joinToString("\n") { mealFood ->
        val portion = mealFood.portion.stripTrailingZeros().toPlainString()
        "- ${convertSingleLine(mealFood.name)} ${portion}인분 (${convertNutritionText(mealFood.nutrition)})"
    }

    private fun createEarlierMealLines(input: CatCommentInput): String {
        if (input.earlierMeals.isEmpty()) {
            return "오늘 첫 식사야."
        }

        return input.earlierMeals.joinToString("\n") { meal ->
            val mealAt = meal.mealAt.format(MEAL_AT_FORMATTER)
            "- $mealAt ${convertSingleLine(meal.name)} (${convertNutritionText(meal.nutrition)})"
        }
    }

    private fun createIntakeLines(current: Nutrition, target: Nutrition): String = listOf(
        "- 칼로리: ${current.calory}kcal / 목표 ${target.calory}kcal",
        "- 탄수화물: ${current.carbs}g / 목표 ${target.carbs}g",
        "- 단백질: ${current.protein}g / 목표 ${target.protein}g",
        "- 지방: ${current.fat}g / 목표 ${target.fat}g",
    ).joinToString("\n")

    private fun createBalanceLines(caloryShareBalance: CaloryShareBalanceResult?): String {
        if (caloryShareBalance == null) {
            return "탄수화물·단백질·지방 기록이 없어. 영양 균형은 언급하지 마."
        }
        if (caloryShareBalance.excessNutrients.isEmpty() && caloryShareBalance.lackingNutrients.isEmpty()) {
            return "탄수화물·단백질·지방이 목표 비율에 가깝게 균형을 이뤘어."
        }

        return listOfNotNull(
            caloryShareBalance.excessNutrients.takeIf { it.isNotEmpty() }
                ?.let { "- 많은 영양소: ${convertNutrientNames(it)}" },
            caloryShareBalance.lackingNutrients.takeIf { it.isNotEmpty() }
                ?.let { "- 부족한 영양소: ${convertNutrientNames(it)}" },
        ).joinToString("\n")
    }

    private fun convertNutrientNames(nutrients: List<MacroNutrient>): String =
        nutrients.joinToString("·") { NUTRIENT_NAMES.getValue(it) }

    private fun convertNutritionText(nutrition: Nutrition): String =
        "${nutrition.calory}kcal, 탄 ${nutrition.carbs}g, 단 ${nutrition.protein}g, 지 ${nutrition.fat}g"

    // 이름은 사용자가 정하는 글이다. 줄바꿈이 프롬프트의 목록 모양을 깨지 않게 한 줄로 편다.
    private fun convertSingleLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun convertCatCommentResponse(resultJson: String): GeminiCatCommentResponse = try {
        objectMapper.readValue(resultJson, GeminiCatCommentResponse::class.java)
    } catch (e: JacksonException) {
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 결과를 해석하지 못했습니다.", e)
    }
}

private data class GeminiCatCommentResponse(val catComment: String = "")

/**
 * 언어마다 다른 코멘트 프롬프트 재료.
 *
 * @property cuisines 다음 식사 추천의 음식 종류 후보. 요청마다 하나를 무작위로 고른다
 * @property instruction 프롬프트 맨 끝에 덧붙일 출력 언어 지시. 한국어는 덧붙이지 않아 null이다
 */
private data class OutputLanguagePrompt(val cuisines: List<String>, val instruction: String?)

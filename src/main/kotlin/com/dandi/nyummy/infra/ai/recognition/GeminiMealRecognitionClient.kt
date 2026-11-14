package com.dandi.nyummy.infra.ai.recognition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.createGeminiImagePart
import com.dandi.nyummy.infra.ai.createGeminiTextPart
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealInputCategory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

@Component
class GeminiMealRecognitionClient(
    private val geminiClient: GeminiClient,
    private val aiProperties: AiProperties,
    private val objectMapper: ObjectMapper,
) : MealRecognitionClient {

    companion object {

        // 프롬프트나 응답 스키마를 바꾸면 올린다. 스냅샷에 남겨 사용자가 고친 비율을 프롬프트별로 나눠 본다.
        private const val PROMPT_VERSION = "v1"

        // 인분은 숫자로 받으면 0.5 단위를 어긴다(0.8 등). 고를 수 있는 값을 스키마로 묶는다.
        private val PORTIONS = generateSequence(MealFood.MIN_PORTION) { it + MealFood.PORTION_STEP }
            .takeWhile { it <= MealFood.MAX_PORTION }
            .map { it.stripTrailingZeros().toPlainString() }
            .toList()

        private const val PROMPT =
            """
        이 사진에서 사용자가 먹은(또는 먹을) 음식을 알아내 JSON으로 답해.

        [inputCategory] 사진이 무엇을 찍은 것인지 하나를 골라.
        FOOD_PHOTO 실제 음식(먹기 전, 먹는 중, 조금 남은 것)
        PACKAGE 과자·음료·간편식 포장이나 제품
        NUTRITION_LABEL 영양성분표 숫자가 보이는 라벨
        TEXT_RECORD 영수증, 주문·배달 앱 화면, 메뉴판, 먹은 것을 적은 글
        ILLUSTRATION 음식 그림이나 모형
        NOT_FOOD 음식과 전혀 관련 없는 사진, 무엇인지 알아볼 수 없는 사진
        음식과 관련이 조금이라도 있으면 NOT_FOOD를 고르지 마.
        여러 개가 함께 보이면 가장 큰 비중을 고르되, 실제 음식이 보이면 FOOD_PHOTO를 골라.

        [foods] 먹은 음식을 하나씩 나눠 적어. NOT_FOOD면 빈 배열이야.
        - 한 항목에는 음식 하나만 적어. 서로 다른 음식은 같은 칸이나 같은 그릇에 있어도 따로 적어(예: 밥과 김치, 밥 옆의 후식).
        - 소스·양념·드레싱은 따로 적지 않고 그 음식에 포함해.
        - name은 흔히 부르는 음식 이름으로 30자 이내로 적어. 포장·제품과 영양성분표는 제품 이름을 적어.
        - portion은 사진에 보이는 그 음식의 양 전체가 일반적인 1인분의 몇 배인지야. 여러 사람이 나눠 먹을 양이면 그만큼 크게 적어(큰 접시에 2인분쯤 담겨 있으면 2). 영수증·주문 화면은 적힌 수량을 써.
        - 같은 음식이 여러 그릇에 있으면 한 줄로 합쳐 적어.
        - 이 식사를 대표하는 음식(주메뉴)을 맨 위에 적어.
        - 10개를 넘으면 양이 많은 것부터 10개만 적어.

        [name] 이 식사를 부를 이름을 20자 이내로 적어. NOT_FOOD면 빈 문자열이야.
    """

        // 한국어가 아닌 언어는 위의 한국어 지시를 그대로 두고, 출력 언어 지시를 맨 끝에 덧붙인다.
        private const val JA_OUTPUT_LANGUAGE_PROMPT =
            """
        [출력 언어]
        name과 foods의 name은 모두 일본어로 써. 한국어를 섞지 마.
        일본에서 흔히 부르는 음식 이름으로 써. (예: 김치찌개 → キムチチゲ)
    """

        // 영어는 같은 이름이 한국어보다 길어 20자를 넘기기 쉽다. 넘으면 서버가 단어 중간에서 자르게 되므로 짧은 이름을 고르게 한다.
        private const val EN_OUTPUT_LANGUAGE_PROMPT =
            """
        [출력 언어]
        name과 foods의 name은 모두 영어로 써. 한국어를 섞지 마.
        영어권에서 통하는 음식 이름으로 써. (예: 김치찌개 → Kimchi stew)
        식사 이름([name])은 띄어쓰기를 포함해 영어 20글자를 넘기지 마. 넘을 것 같으면 더 짧은 이름을 골라. (예: Bulgogi set)
    """

        // 모델은 스키마에 적은 순서대로 쓴다. 음식 줄을 먼저 쓰게 해 대표 이름이 그 줄들을 보고 정해지게 한다.
        private val RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "inputCategory" to mapOf("type" to "STRING", "enum" to MealInputCategory.entries.map { it.name }),
                "foods" to mapOf(
                    "type" to "ARRAY",
                    "maxItems" to MealFood.MAX_COUNT,
                    "items" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "name" to mapOf("type" to "STRING"),
                            "portion" to mapOf("type" to "STRING", "enum" to PORTIONS),
                        ),
                        "required" to listOf("name", "portion"),
                    ),
                ),
                "name" to mapOf("type" to "STRING"),
            ),
            "required" to listOf("inputCategory", "foods", "name"),
            "propertyOrdering" to listOf("inputCategory", "foods", "name"),
        )

        private val logger = LoggerFactory.getLogger(GeminiMealRecognitionClient::class.java)
    }

    override fun recognizeMeal(
        imageBytes: ByteArray,
        contentType: String,
        language: Language,
        timeout: Duration?,
    ): MealRecognitionResult {
        val resultJson = geminiClient.createContent(
            model = aiProperties.recognitionModel,
            parts = listOf(
                createGeminiImagePart(imageBytes, contentType),
                createGeminiTextPart(createPrompt(language)),
            ),
            responseSchema = RESPONSE_SCHEMA,
            timeout = timeout,
        )
        val response = convertRecognitionResponse(resultJson)
        val foods = convertFoods(response.foods)

        // 음식 줄이 하나도 없으면 입력 유형이 무엇이든 계산할 것이 없으므로 음식이 아닌 것으로 본다.
        if (response.inputCategory == MealInputCategory.NOT_FOOD || foods.isEmpty()) {
            logger.info(
                "음식이 아닌 사진으로 인식했습니다: inputCategory={}, foodCount={}",
                response.inputCategory,
                foods.size,
            )
            throw AnalysisFailureException(AnalysisFailureCategory.NOT_FOOD, "음식이 아닙니다.")
        }

        return MealRecognitionResult(
            inputCategory = response.inputCategory,
            // 대표 이름이 비어 오면 대표 음식(맨 위 줄)의 이름을 쓴다.
            name = response.name.trim().take(Meal.NAME_MAX_LENGTH).ifEmpty {
                foods.first().name.take(Meal.NAME_MAX_LENGTH)
            },
            foods = foods,
            model = aiProperties.recognitionModel,
            promptVersion = PROMPT_VERSION,
        )
    }

    private fun createPrompt(language: Language): String = when (language) {
        Language.KO -> PROMPT
        Language.JA -> "$PROMPT\n$JA_OUTPUT_LANGUAGE_PROMPT"
        Language.EN -> "$PROMPT\n$EN_OUTPUT_LANGUAGE_PROMPT"
    }

    private fun convertRecognitionResponse(resultJson: String): GeminiMealRecognitionResponse = try {
        objectMapper.readValue(resultJson, GeminiMealRecognitionResponse::class.java)
    } catch (e: JacksonException) {
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 결과를 해석하지 못했습니다.", e)
    }

    /**
     * 모델이 낸 음식 줄을 저장할 수 있는 모양으로 맞춘다. 스키마로 묶어도 어길 수 있어 서버에서 한 번 더 맞춘다.
     * 이름이 빈 줄은 빼고, 이름은 [MealFood.NAME_MAX_LENGTH]자에서 자르고, [MealFood.MAX_COUNT]줄까지만 받는다.
     */
    private fun convertFoods(foods: List<GeminiRecognizedFood>): List<MealFoodRecognitionResult> = foods
        .map { it.copy(name = it.name.trim()) }
        .filter { it.name.isNotEmpty() }
        .take(MealFood.MAX_COUNT)
        .map {
            MealFoodRecognitionResult(
                name = it.name.take(MealFood.NAME_MAX_LENGTH),
                portion = convertPortion(it.portion),
            )
        }

    /** 인분을 가장 가까운 0.5 단위로 맞추고 [MealFood.MIN_PORTION]~[MealFood.MAX_PORTION] 안으로 넣는다. */
    private fun convertPortion(portion: String): BigDecimal {
        val value = portion.trim().toBigDecimalOrNull()
            ?: throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "인분이 숫자가 아닙니다: $portion")
        val steps = value.divide(MealFood.PORTION_STEP, 0, RoundingMode.HALF_UP)

        return (steps * MealFood.PORTION_STEP).coerceIn(MealFood.MIN_PORTION, MealFood.MAX_PORTION)
    }
}

private data class GeminiMealRecognitionResponse(
    val inputCategory: MealInputCategory,
    val foods: List<GeminiRecognizedFood> = emptyList(),
    val name: String = "",
)

private data class GeminiRecognizedFood(val name: String, val portion: String)

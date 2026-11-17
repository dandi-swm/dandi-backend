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
import com.dandi.nyummy.meal.dto.NutritionHint
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
        private const val PROMPT_VERSION = "v2"

        // 영양성분표는 제품으로 판정한다. 값만 남겨 두는 것은 예전에 저장된 행을 읽기 위해서다.
        private val INPUT_CATEGORIES = MealInputCategory.entries - MealInputCategory.NUTRITION_LABEL

        // 1인분 컬럼(DECIMAL(7,2))에 담을 수 있는 가장 큰 값
        private val MAX_NUTRITION_HINT_VALUE = BigDecimal("99999.99")

        private val NUTRITION_HINT_FIELDS = listOf("calory", "carbs", "protein", "fat")

        private const val PROMPT =
            """
        이 사진에서 사용자가 먹은(또는 먹을) 음식을 알아내 JSON으로 답해.

        [inputCategory] 사진이 무엇을 찍은 것인지 하나를 골라.
        FOOD_PHOTO 실제 음식(먹기 전, 먹는 중, 조금 남은 것)
        PACKAGE 과자·음료·간편식 포장이나 제품, 그 제품의 영양성분표
        TEXT_RECORD 영수증, 주문·배달 앱 화면, 메뉴판, 먹은 것을 적은 글
        ILLUSTRATION 음식 그림이나 모형
        NOT_FOOD 음식과 전혀 관련 없는 사진, 무엇인지 알아볼 수 없는 사진
        음식과 관련이 조금이라도 있으면 NOT_FOOD를 고르지 마.
        여러 개가 함께 보이면 가장 큰 비중을 고르되, 실제 음식이 보이면 FOOD_PHOTO를 골라.

        [foods] 먹은 음식을 하나씩 나눠 적어. NOT_FOOD면 빈 배열이야.
        - 한 항목에는 음식 하나만 적어. 서로 다른 음식은 같은 칸이나 같은 그릇에 있어도 따로 적어(예: 밥과 김치, 밥 옆의 후식).
        - 소스·양념·드레싱은 따로 적지 않고 그 음식에 포함해.
        - name은 흔히 부르는 음식 이름으로 30자 이내로 적어. 포장·제품과 영양성분표는 제품 이름을 적어.
        - 같은 음식이 여러 그릇에 있으면 한 줄로 합쳐 적어.
        - calory·carbs·protein·fat은 사진에 그 음식의 값으로 적혀 있는 숫자만 옮겨 적어. calory는 kcal, 나머지는 g이야.
          음식 옆에 직접 적어 둔 숫자, 메뉴판·주문 화면에 적힌 숫자, 영양성분표의 총 내용량 기준 숫자가 여기에 들어가.
          적혀 있지 않은 항목은 null로 둬. 사진에 적힌 숫자가 없는데 음식만 보고 짐작한 값은 절대 적지 마.
          100g당·100ml당처럼 그 음식 하나 전체의 값이 아닌 숫자는 적지 말고 null로 둬.
          음식이 둘 이상인데 숫자가 음식마다 적혀 있지 않고 한 벌만 적혀 있으면(예: "총 칼로리 650"), 그것은 식사 전체의 값이야.
          식사 전체의 값은 어느 음식에도 적지 말고 모든 음식의 값을 null로 둬. 나눠서 적지도 마.
          음식이 하나뿐이면 적혀 있는 값이 그 음식의 값이니 그대로 적어.
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
                "inputCategory" to mapOf("type" to "STRING", "enum" to INPUT_CATEGORIES.map { it.name }),
                "foods" to mapOf(
                    "type" to "ARRAY",
                    "maxItems" to MealFood.MAX_COUNT,
                    "items" to mapOf(
                        "type" to "OBJECT",
                        // 힌트는 적혀 있을 때만 오므로 필수가 아니고 null일 수 있다.
                        "properties" to mapOf("name" to mapOf("type" to "STRING")) +
                            NUTRITION_HINT_FIELDS.associateWith { mapOf("type" to "NUMBER", "nullable" to true) },
                        "required" to listOf("name"),
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
     * 힌트는 저장할 수 있는 값만 남긴다([convertNutritionHint]).
     */
    private fun convertFoods(foods: List<GeminiRecognizedFood>): List<MealFoodRecognitionResult> = foods
        .map { it.copy(name = it.name.trim()) }
        .filter { it.name.isNotEmpty() }
        .take(MealFood.MAX_COUNT)
        .map {
            MealFoodRecognitionResult(
                name = it.name.take(MealFood.NAME_MAX_LENGTH),
                nutritionHint = convertNutritionHint(it),
            )
        }

    /**
     * 모델이 읽은 힌트를 1인분 컬럼에 담을 수 있는 값(소수 둘째 자리)으로 맞춘다.
     * 음수이거나 컬럼에 담을 수 없게 큰 값은 잘못 읽은 것으로 보고 그 항목만 버린다. 숫자 하나 때문에 인식 전체를 실패로 돌리지 않는다.
     *
     * @return 남은 항목이 하나도 없으면 null
     */
    private fun convertNutritionHint(food: GeminiRecognizedFood): NutritionHint? {
        fun convertNutritionHintValue(value: BigDecimal?): BigDecimal? = value
            ?.setScale(2, RoundingMode.HALF_UP)
            ?.takeIf { it >= BigDecimal.ZERO && it <= MAX_NUTRITION_HINT_VALUE }

        val nutritionHint = NutritionHint(
            calory = convertNutritionHintValue(food.calory),
            carbs = convertNutritionHintValue(food.carbs),
            protein = convertNutritionHintValue(food.protein),
            fat = convertNutritionHintValue(food.fat),
        )

        return nutritionHint.takeIf { listOfNotNull(it.calory, it.carbs, it.protein, it.fat).isNotEmpty() }
    }
}

private data class GeminiMealRecognitionResponse(
    val inputCategory: MealInputCategory,
    val foods: List<GeminiRecognizedFood> = emptyList(),
    val name: String = "",
)

private data class GeminiRecognizedFood(
    val name: String,
    val calory: BigDecimal? = null,
    val carbs: BigDecimal? = null,
    val protein: BigDecimal? = null,
    val fat: BigDecimal? = null,
)

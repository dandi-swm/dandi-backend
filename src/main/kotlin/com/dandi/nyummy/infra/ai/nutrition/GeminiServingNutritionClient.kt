package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.createGeminiTextPart
import com.dandi.nyummy.meal.dto.IconSummaryResult
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.Icon
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.service.IconService
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 음식 이름으로 1인분 영양과 아이콘을 Gemini에 묻는다. 사진은 넘기지 않는다.
 * 음식 DB가 생기기 전까지 1인분 값을 대는 자리다.
 */
@Component
class GeminiServingNutritionClient(
    private val geminiClient: GeminiClient,
    private val aiProperties: AiProperties,
    private val iconService: IconService,
    private val objectMapper: ObjectMapper,
) : ServingNutritionClient {

    companion object {

        // 이 유형의 식사는 음식 줄 이름이 제품 이름이다. 일반적인 1인분이 아니라 그 제품의 영양성분표 값을 묻는다.
        private val PRODUCT_INPUT_CATEGORIES = setOf(MealInputCategory.PACKAGE, MealInputCategory.NUTRITION_LABEL)

        // 1인분 컬럼(DECIMAL(7,2))에 담을 수 있는 가장 큰 값
        private val MAX_SERVING_VALUE = BigDecimal("99999.99")

        private const val FOOD_PROMPT =
            """
        다음 음식들의 일반적인 1인분 영양 정보를 JSON으로 답해.

        음식 목록:
        {foods}

        - 음식마다 일반적인 1인분의 칼로리(kcal)와 탄수화물·단백질·지방(g)을 실제 값에 가깝게 적어.
        - calory ≈ carbs*4 + protein*4 + fat*9 를 만족시켜.
        - index는 위 목록의 번호를 그대로 적고, 모든 음식을 한 번씩 적어.
        - iconId는 아래 아이콘 목록에서 그 음식에 가장 맞는 것을 골라. 뚜렷하게 맞는 것이 없으면 ${Icon.DEFAULT_ID}(밥)를 골라.

        음식 아이콘 목록: {icons}
    """

        private const val PRODUCT_PROMPT =
            """
        다음은 포장된 제품의 이름이야. 그 제품의 영양성분표에 적힌 값을 JSON으로 답해.

        제품 목록:
        {foods}

        - 제품마다 칼로리(kcal)와 탄수화물·단백질·지방(g)을 적어. 한 번에 다 먹는 제품은 총 내용량 기준으로 적어. 모르는 제품이면 비슷한 제품의 일반적인 값을 적어.
        - calory ≈ carbs*4 + protein*4 + fat*9 를 만족시켜.
        - index는 위 목록의 번호를 그대로 적고, 모든 제품을 한 번씩 적어.
        - iconId는 아래 아이콘 목록에서 그 제품에 가장 맞는 것을 골라. 뚜렷하게 맞는 것이 없으면 ${Icon.DEFAULT_ID}(밥)를 골라.

        음식 아이콘 목록: {icons}
    """

        private val RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "foods" to mapOf(
                    "type" to "ARRAY",
                    "items" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "index" to mapOf("type" to "INTEGER"),
                            "calory" to mapOf("type" to "NUMBER"),
                            "carbs" to mapOf("type" to "NUMBER"),
                            "protein" to mapOf("type" to "NUMBER"),
                            "fat" to mapOf("type" to "NUMBER"),
                            "iconId" to mapOf("type" to "INTEGER"),
                        ),
                        "required" to listOf("index", "calory", "carbs", "protein", "fat", "iconId"),
                    ),
                ),
            ),
            "required" to listOf("foods"),
        )
    }

    override fun getServingNutritions(
        names: List<String>,
        inputCategory: MealInputCategory,
    ): List<ServingNutritionResult> {
        if (names.isEmpty()) {
            return emptyList()
        }
        val icons = getIcons()

        val resultJson = geminiClient.createContent(
            model = aiProperties.servingNutritionModel,
            parts = listOf(createGeminiTextPart(createPrompt(names, inputCategory, icons))),
            responseSchema = RESPONSE_SCHEMA,
        )
        // 음식은 이름이 아니라 번호로 짝짓는다. 모델이 이름을 조금 바꿔 적어도 어느 줄의 값인지 알 수 있다.
        val servingsByIndex = convertServingNutritionResponse(resultJson).foods.associateBy { it.index }
        val iconIds = icons.map { it.id }.toSet()

        return names.indices.map { order ->
            val serving = servingsByIndex[order + 1]
                ?: throw AnalysisFailureException(
                    AnalysisFailureCategory.INVALID_RESPONSE,
                    "1인분 조회 결과에 ${order + 1}번 음식이 없습니다.",
                )

            ServingNutritionResult(
                servingNutrition = ServingNutrition(
                    calory = convertServingValue(serving.calory),
                    carbs = convertServingValue(serving.carbs),
                    protein = convertServingValue(serving.protein),
                    fat = convertServingValue(serving.fat),
                ),
                // 목록에 없는 아이콘을 지어내면 기본 아이콘으로 바꾼다. 없는 id는 음식 줄에 저장할 수 없다.
                iconId = serving.iconId.takeIf { it in iconIds } ?: Icon.DEFAULT_ID,
            )
        }
    }

    private fun createPrompt(
        names: List<String>,
        inputCategory: MealInputCategory,
        icons: List<IconSummaryResult>,
    ): String {
        val prompt = if (inputCategory in PRODUCT_INPUT_CATEGORIES) PRODUCT_PROMPT else FOOD_PROMPT
        // 이름은 사용자가 고칠 수 있는 글이다. 줄바꿈이 목록 모양을 깨지 않게 한 줄로 편다.
        val foods = names.withIndex().joinToString("\n") { (order, name) ->
            "${order + 1}. ${name.replace(Regex("\\s+"), " ").trim()}"
        }

        return prompt
            .replace("{foods}", foods)
            .replace("{icons}", objectMapper.writeValueAsString(icons))
    }

    /** 프롬프트에 넣을 아이콘 목록. DB 조회 실패는 잠시 뒤 다시 하면 될 수 있다. */
    private fun getIcons(): List<IconSummaryResult> = try {
        iconService.getIcons()
    } catch (e: DataAccessException) {
        throw AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "아이콘 목록을 조회하지 못했습니다.", e)
    }

    private fun convertServingNutritionResponse(resultJson: String): GeminiServingNutritionResponse = try {
        objectMapper.readValue(resultJson, GeminiServingNutritionResponse::class.java)
    } catch (e: JacksonException) {
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 결과를 해석하지 못했습니다.", e)
    }

    /** 1인분 값을 저장할 수 있는 모양(소수 둘째 자리)으로 맞춘다. 음수이거나 컬럼에 담을 수 없게 크면 믿을 수 없는 결과로 본다. */
    private fun convertServingValue(value: BigDecimal): BigDecimal {
        val rounded = value.setScale(2, RoundingMode.HALF_UP)
        if (rounded < BigDecimal.ZERO || rounded > MAX_SERVING_VALUE) {
            throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "1인분 값이 범위를 벗어났습니다: $value")
        }

        return rounded
    }
}

private data class GeminiServingNutritionResponse(val foods: List<GeminiServingNutrition> = emptyList())

private data class GeminiServingNutrition(
    val index: Int,
    val calory: BigDecimal,
    val carbs: BigDecimal,
    val protein: BigDecimal,
    val fat: BigDecimal,
    val iconId: Long,
)

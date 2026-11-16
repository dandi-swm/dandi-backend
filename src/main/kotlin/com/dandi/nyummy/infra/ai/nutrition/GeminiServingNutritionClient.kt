package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.GeminiClient
import com.dandi.nyummy.infra.ai.createGeminiImagePart
import com.dandi.nyummy.infra.ai.createGeminiTextPart
import com.dandi.nyummy.meal.dto.IconSummaryResult
import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.PhotoServingNutritionInput
import com.dandi.nyummy.meal.dto.PhotoServingNutritionResult
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.Icon
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.service.IconService
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 음식의 1인분 영양과 아이콘을 Gemini에 묻는다. 이름만으로 묻는 호출과, 음식 사진을 함께 보내 인분까지 묻는 호출이 있다.
 */
@Component
class GeminiServingNutritionClient(
    private val geminiClient: GeminiClient,
    private val aiProperties: AiProperties,
    private val iconService: IconService,
    private val objectMapper: ObjectMapper,
) : ServingNutritionClient {

    companion object {

        // 1인분 컬럼(DECIMAL(7,2))에 담을 수 있는 가장 큰 값
        private val MAX_SERVING_VALUE = BigDecimal("99999.99")

        // 음식이 아닌 이름의 결과. 값을 지어내지 않고 0으로 채워, 식사는 그대로 끝나고 그 줄만 0으로 보이게 한다.
        // 모델이 0이 아닌 값을 함께 적어도 쓰지 않는다.
        private val NOT_FOOD_VALUE = BigDecimal("0.00")
        private val NOT_FOOD_RESULT = ServingNutritionResult(
            servingNutrition = ServingNutrition(NOT_FOOD_VALUE, NOT_FOOD_VALUE, NOT_FOOD_VALUE, NOT_FOOD_VALUE),
            iconId = Icon.DEFAULT_ID,
            isFood = false,
        )

        private const val FOOD_PROMPT =
            """
        다음 음식들의 일반적인 1인분 영양 정보를 JSON으로 답해.

        음식 목록:
        {foods}

        - 음식마다 일반적인 1인분의 칼로리(kcal)와 탄수화물·단백질·지방(g)을 실제 값에 가깝게 적어.
        - calory ≈ carbs*4 + protein*4 + fat*9 를 만족시켜.
        - index는 위 목록의 번호를 그대로 적고, 모든 음식을 한 번씩 적어.
        - isFood는 그 이름이 사람이 먹거나 마시는 것이면 true, 아니면(물건, 뜻 없는 글자 등) false야. false면 값을 모두 0으로 적어.
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
        - isFood는 그 이름이 사람이 먹거나 마시는 것이면 true, 아니면(물건, 뜻 없는 글자 등) false야. false면 값을 모두 0으로 적어.
        - iconId는 아래 아이콘 목록에서 그 제품에 가장 맞는 것을 골라. 뚜렷하게 맞는 것이 없으면 ${Icon.DEFAULT_ID}(밥)를 골라.

        음식 아이콘 목록: {icons}
    """

        // 인분은 숫자로 받으면 0.5 단위를 어긴다(0.8 등). 고를 수 있는 값을 스키마로 묶는다.
        private val PORTIONS = generateSequence(MealFood.MIN_PORTION) { it + MealFood.PORTION_STEP }
            .takeWhile { it <= MealFood.MAX_PORTION }
            .map { it.stripTrailingZeros().toPlainString() }
            .toList()

        private val NUTRIENT_FIELDS = listOf("calory", "carbs", "protein", "fat")

        private const val PHOTO_PROMPT =
            """
        이 사진은 사용자가 먹은(또는 먹을) 식사야. 사진을 보고 아래 음식들의 영양 정보를 JSON으로 답해.

        음식 목록:
        {foods}

        [foods] 음식마다 한 번씩 적어. index는 위 목록의 번호를 그대로 적어.
        - calory·carbs·protein·fat은 사진 속 그 음식의 재료와 조리법을 보고 정한 1인분의 칼로리(kcal)와 탄수화물·단백질·지방(g)이야. 실제 값에 가깝게 적어.
        - calory ≈ carbs*4 + protein*4 + fat*9 를 만족시켜.
        - portion은 사진에 보이는 그 음식의 양 전체가 방금 적은 1인분의 몇 배인지야. 여러 사람이 나눠 먹을 양이면 그만큼 크게 적어(큰 접시에 2인분쯤 담겨 있으면 2).
        - 목록에 "사진에 적힌 값"이 붙은 음식은 그 값을 그대로 적고, 나머지 항목만 그 값에 어울리게 채워. 이 음식의 portion은 1이야.
        - 사진에서 찾을 수 없는 음식은 일반적인 1인분 값을 적고 portion은 1로 둬.
        - isFood는 그 이름이 사람이 먹거나 마시는 것이면 true, 아니면(물건, 뜻 없는 글자 등) false야. false면 값을 모두 0으로 적어.
        - iconId는 아래 아이콘 목록에서 그 음식에 가장 맞는 것을 골라. 뚜렷하게 맞는 것이 없으면 ${Icon.DEFAULT_ID}(밥)를 골라.

        [total] 사진에 음식 하나가 아니라 식사 전체의 합계로 적혀 있는 값(예: "총 칼로리 650")이 있으면 그 숫자를 그대로 옮겨 적어.
        - 적혀 있지 않은 항목은 null로 둬. 합계가 적혀 있지 않으면 모두 null이야.
        - 음식들의 값을 더해서 만들지 마. 사진에 적힌 숫자만 옮겨.
        - 합계가 적혀 있어도 [foods]의 값은 위 규칙대로 네가 본 대로 적어. 합계에 맞추는 것은 하지 않아도 돼.

        음식 아이콘 목록: {icons}
    """

        private val PHOTO_RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "foods" to mapOf(
                    "type" to "ARRAY",
                    "items" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf("index" to mapOf("type" to "INTEGER")) +
                            NUTRIENT_FIELDS.associateWith { mapOf("type" to "NUMBER") } +
                            mapOf(
                                "portion" to mapOf("type" to "STRING", "enum" to PORTIONS),
                                "isFood" to mapOf("type" to "BOOLEAN"),
                                "iconId" to mapOf("type" to "INTEGER"),
                            ),
                        "required" to listOf("index") + NUTRIENT_FIELDS + listOf("portion", "isFood", "iconId"),
                    ),
                ),
                "total" to mapOf(
                    "type" to "OBJECT",
                    "properties" to NUTRIENT_FIELDS.associateWith { mapOf("type" to "NUMBER", "nullable" to true) },
                ),
            ),
            "required" to listOf("foods", "total"),
            "propertyOrdering" to listOf("foods", "total"),
        )

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
                            "isFood" to mapOf("type" to "BOOLEAN"),
                            "iconId" to mapOf("type" to "INTEGER"),
                        ),
                        "required" to listOf("index", "calory", "carbs", "protein", "fat", "isFood", "iconId"),
                    ),
                ),
            ),
            "required" to listOf("foods"),
        )
    }

    override fun getNamedServingNutritions(
        names: List<String>,
        inputCategory: MealInputCategory,
    ): List<ServingNutritionResult> {
        if (names.isEmpty()) {
            return emptyList()
        }
        val icons = getIcons()

        val resultJson = geminiClient.createContent(
            model = aiProperties.namedNutritionModel,
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

            if (!serving.isFood) {
                return@map NOT_FOOD_RESULT
            }

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

    override fun getPhotoServingNutritions(
        imageBytes: ByteArray,
        contentType: String,
        foods: List<PhotoServingNutritionInput>,
    ): PhotoServingNutritionResult {
        if (foods.isEmpty()) {
            return PhotoServingNutritionResult(servingNutritions = emptyList())
        }
        val icons = getIcons()

        val resultJson = geminiClient.createContent(
            model = aiProperties.photoNutritionModel,
            parts = listOf(
                createGeminiImagePart(imageBytes, contentType),
                createGeminiTextPart(createPhotoPrompt(foods, icons)),
            ),
            responseSchema = PHOTO_RESPONSE_SCHEMA,
            thinkingLevel = GeminiClient.LOW_THINKING_LEVEL,
        )
        val response = try {
            objectMapper.readValue(resultJson, GeminiPhotoServingNutritionResponse::class.java)
        } catch (e: JacksonException) {
            throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 결과를 해석하지 못했습니다.", e)
        }
        val servingsByIndex = response.foods.associateBy { it.index }
        val iconIds = icons.map { it.id }.toSet()

        return PhotoServingNutritionResult(
            servingNutritions = foods.indices.map { order ->
                val serving = servingsByIndex[order + 1]
                    ?: throw AnalysisFailureException(
                        AnalysisFailureCategory.INVALID_RESPONSE,
                        "1인분 조회 결과에 ${order + 1}번 음식이 없습니다.",
                    )

                if (!serving.isFood) {
                    return@map NOT_FOOD_RESULT.copy(portion = MealFood.DEFAULT_PORTION)
                }

                ServingNutritionResult(
                    servingNutrition = ServingNutrition(
                        calory = convertServingValue(serving.calory),
                        carbs = convertServingValue(serving.carbs),
                        protein = convertServingValue(serving.protein),
                        fat = convertServingValue(serving.fat),
                    ),
                    iconId = serving.iconId.takeIf { it in iconIds } ?: Icon.DEFAULT_ID,
                    portion = convertPortion(serving.portion),
                )
            },
            totalNutritionHint = convertTotalNutritionHint(response.total),
        )
    }

    private fun createPhotoPrompt(foods: List<PhotoServingNutritionInput>, icons: List<IconSummaryResult>): String {
        val foodLines = foods.withIndex().joinToString("\n") { (order, food) ->
            val name = food.name.replace(Regex("\\s+"), " ").trim()
            val writtenValues = food.nutritionHint?.let { hint ->
                listOfNotNull(
                    hint.calory?.let { "칼로리 ${it.toPlainString()}kcal" },
                    hint.carbs?.let { "탄수화물 ${it.toPlainString()}g" },
                    hint.protein?.let { "단백질 ${it.toPlainString()}g" },
                    hint.fat?.let { "지방 ${it.toPlainString()}g" },
                ).joinToString(", ")
            }

            if (writtenValues.isNullOrEmpty()) {
                "${order + 1}. $name"
            } else {
                "${order + 1}. $name (사진에 적힌 값: $writtenValues)"
            }
        }

        return PHOTO_PROMPT
            .replace("{foods}", foodLines)
            .replace("{icons}", objectMapper.writeValueAsString(icons))
    }

    /** 인분을 가장 가까운 0.5 단위로 맞추고 [MealFood.MIN_PORTION]~[MealFood.MAX_PORTION] 안으로 넣는다. 스키마로 묶어도 어길 수 있다. */
    private fun convertPortion(portion: String): BigDecimal {
        val value = portion.trim().toBigDecimalOrNull()
            ?: throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "인분이 숫자가 아닙니다: $portion")
        val steps = value.divide(MealFood.PORTION_STEP, 0, RoundingMode.HALF_UP)

        return (steps * MealFood.PORTION_STEP).coerceIn(MealFood.MIN_PORTION, MealFood.MAX_PORTION)
    }

    /**
     * 사진에 적힌 식사 전체의 값을 맞춘다. 음수이거나 지나치게 큰 값은 잘못 읽은 것으로 보고 그 항목만 버린다.
     *
     * @return 남은 항목이 하나도 없으면 null
     */
    private fun convertTotalNutritionHint(total: GeminiTotalNutrition?): NutritionHint? {
        fun convertTotalValue(value: BigDecimal?): BigDecimal? = value
            ?.setScale(2, RoundingMode.HALF_UP)
            ?.takeIf { it >= BigDecimal.ZERO && it <= MAX_SERVING_VALUE }

        val hint = NutritionHint(
            calory = convertTotalValue(total?.calory),
            carbs = convertTotalValue(total?.carbs),
            protein = convertTotalValue(total?.protein),
            fat = convertTotalValue(total?.fat),
        )

        return hint.takeIf { listOfNotNull(it.calory, it.carbs, it.protein, it.fat).isNotEmpty() }
    }

    private fun createPrompt(
        names: List<String>,
        inputCategory: MealInputCategory,
        icons: List<IconSummaryResult>,
    ): String {
        // 제품 유형의 식사는 음식 줄 이름이 제품 이름이다. 일반적인 1인분이 아니라 그 제품의 영양성분표 값을 묻는다.
        val prompt = if (inputCategory.isPackage) PRODUCT_PROMPT else FOOD_PROMPT
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
    val isFood: Boolean = true,
)

private data class GeminiPhotoServingNutritionResponse(
    val foods: List<GeminiPhotoServingNutrition> = emptyList(),
    val total: GeminiTotalNutrition? = null,
)

private data class GeminiPhotoServingNutrition(
    val index: Int,
    val calory: BigDecimal,
    val carbs: BigDecimal,
    val protein: BigDecimal,
    val fat: BigDecimal,
    val portion: String,
    val iconId: Long,
    val isFood: Boolean = true,
)

private data class GeminiTotalNutrition(
    val calory: BigDecimal? = null,
    val carbs: BigDecimal? = null,
    val protein: BigDecimal? = null,
    val fat: BigDecimal? = null,
)

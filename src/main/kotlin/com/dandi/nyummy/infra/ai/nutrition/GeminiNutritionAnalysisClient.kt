package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.aws.s3.S3Service
import com.dandi.nyummy.meal.dto.Nutrition
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import kotlin.io.encoding.Base64

@Component
class GeminiNutritionAnalysisClient(
    private val restClient: RestClient,
    private val aiProperties: AiProperties,
    private val s3Service: S3Service,
    private val objectMapper: ObjectMapper,
) : NutritionAnalysisClient {

    companion object {

        private const val PROMPT =
            """이 음식 사진을 분석해서 20자 이내의 음식 이름을 지어주고,
            주어진 아이콘 목록에서 음식과 가장 가까운 것 하나의 id를 골라줘.
            그리고 총 칼로리(kcal), 탄수화물(g), 단백질(g), 지방(g)을 정수로 추정해줘.
            이때 다음 규칙을 지켜줘: 칼로리 ≈ 탄수화물*4 + 단백질*4 + 지방*9"""

        private val RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "name" to mapOf("type" to "STRING"),
                "iconId" to mapOf("type" to "INTEGER"),
                "calory" to mapOf("type" to "INTEGER"),
                "carbs" to mapOf("type" to "INTEGER"),
                "protein" to mapOf("type" to "INTEGER"),
                "fat" to mapOf("type" to "INTEGER"),
            ),
            "required" to listOf("name", "iconId", "calory", "carbs", "protein", "fat"),
        )
    }

    override fun analyzeNutrition(imageKey: String): NutritionAnalysisResult {
        val objectContent = s3Service.downloadObject(imageKey)
        val encodedContent = Base64.encode(objectContent.bytes, 0, objectContent.bytes.size)
        val mimeType = objectContent.contentType

        // TODO: DB에서 icon 읽어옴.
        val icons = mapOf(
            1 to "샐러드",
            3 to "샌드위치",
            4 to "떡볶이",
            5 to "밥",
        )

        val prompt = "$PROMPT 음식 아이콘 목록은 다음과 같아: $icons 만약 뚜렷하게 맞는 것이 없으면 5(밥)를 골라줘."

        val requestBody = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf(
                            "inlineData" to mapOf(
                                "mimeType" to mimeType,
                                "data" to encodedContent,
                            ),
                        ),
                        mapOf("text" to prompt),
                    ),
                ),
            ),
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "responseSchema" to RESPONSE_SCHEMA,
            ),
        )

        val response = restClient.post()
            .uri("/v1beta/models/{model}:generateContent", aiProperties.model)
            .header("x-goog-api-key", aiProperties.apiKey)
            .body(requestBody)
            .retrieve()
            .body(GeminiGenerateContentResponse::class.java)
            ?: throw IllegalStateException("Gemini 응답이 비어 있습니다.")

        val resultJson = response.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?: throw IllegalStateException("Gemini 응답에 결과가 없습니다.")

        val parsed = objectMapper.readValue(resultJson, GeminiNutritionResponse::class.java)

        return NutritionAnalysisResult(
            name = parsed.name,
            iconId = parsed.iconId,
            nutrition = Nutrition(parsed.calory, parsed.carbs, parsed.protein, parsed.fat),
        )
    }
}

data class GeminiGenerateContentResponse(val candidates: List<GeminiCandidate> = emptyList())
data class GeminiCandidate(val content: GeminiContent?)
data class GeminiContent(val parts: List<GeminiPart> = emptyList())
data class GeminiPart(val text: String?)
private data class GeminiNutritionResponse(
    val name: String,
    val iconId: Long,
    val calory: Int,
    val carbs: Int,
    val protein: Int,
    val fat: Int,
)

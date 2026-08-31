package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.aws.s3.S3Service
import com.dandi.nyummy.meal.dto.Nutrition
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
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
            """
                이 사진이 사용자가 실제로 섭취한 음식의 사진인지 판단하고, 아래 JSON만 출력해.
                설명이나 마크다운 코드블록 없이 JSON 객체만 반환해.

                [isFood 판단 기준]
                true: 촬영자 앞에 실제로 놓인, 지금 먹으려는(또는 먹은) 실물 음식
                false: 위에 해당하지 않는 모든 경우

                false로 판단해야 하는 예시:
                - 그림, 일러스트, 3D 렌더링 등 실물이 아닌 음식
                - 화면이나 인쇄물에 찍힌 음식 (메뉴판, 광고, 스마트폰 화면, 책)
                - 영양성분표, 성분 라벨, 텍스트가 주된 사진
                - 진열용 음식 모형, 플라스틱 샘플
                - 반려동물 사료
                - 음식이 남아있지 않은 빈 그릇

                [출력 형식]
                {
                  "isFood": boolean,
                  "rejectReason": string | null,   // isFood가 false일 때만, 위 예시 중 어디에 해당하는지
                  "name": string | null,            // 20자 이내 음식 이름
                  "iconId": number | null,
                  "calory": number | null,
                  "carbs": number | null,
                  "protein": number | null,
                  "fat": number | null
                }

                isFood가 false면 name 이하 필드는 모두 null로 채워.
                영양소 추정 시 calory ≈ carbs*4 + protein*4 + fat*9 를 만족시켜.
            """

        private val RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "isFood" to mapOf("type" to "BOOLEAN"),
                "rejectReason" to mapOf("type" to "STRING"),
                "name" to mapOf("type" to "STRING"),
                "iconId" to mapOf("type" to "INTEGER"),
                "calory" to mapOf("type" to "INTEGER"),
                "carbs" to mapOf("type" to "INTEGER"),
                "protein" to mapOf("type" to "INTEGER"),
                "fat" to mapOf("type" to "INTEGER"),
            ),
            "required" to listOf("isFood", "rejectReason", "name", "iconId", "calory", "carbs", "protein", "fat"),
        )

        private val logger = LoggerFactory.getLogger(GeminiNutritionAnalysisClient::class.java)
    }

    override fun analyzeNutrition(imageKey: String): NutritionAnalysisResult {
        val objectContent = runBlocking { s3Service.downloadObject(imageKey) }
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

        if (!parsed.isFood) {
            logger.info("영양 분석에 실패했습니다: imageKey={}, rejectReason={}", imageKey, parsed.rejectReason)
            throw IllegalStateException("음식이 아닙니다.")
        }

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
    val isFood: Boolean,
    val rejectReason: String,
    val name: String,
    val iconId: Long,
    val calory: Int,
    val carbs: Int,
    val protein: Int,
    val fat: Int,
)

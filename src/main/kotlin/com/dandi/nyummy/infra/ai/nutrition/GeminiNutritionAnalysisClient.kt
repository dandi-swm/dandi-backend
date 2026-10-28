package com.dandi.nyummy.infra.ai.nutrition

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.repository.IconRepository
import com.dandi.nyummy.meal.service.IconService
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
    private val s3StorageClient: S3StorageClient,
    private val objectMapper: ObjectMapper,
    private val iconService: IconService,
) : NutritionAnalysisClient {

    companion object {

        private val RECOMMENDATION_CUISINES = listOf("한식", "중식", "일식", "양식", "분식")
        private val RECOMMENDATION_DISH_FORMS = listOf("밥·덮밥", "면", "국·찌개·탕", "구이·조림 정식", "볶음")

        private const val PROMPT =
            """
        이 이미지에서 사용자가 먹은(또는 먹을) 음식을 알아내고, 아래 JSON만 출력해.
        설명이나 마크다운 코드블록 없이 JSON 객체만 반환해.

        [isFood 판단 기준]
        이미지에서 어떤 음식인지 알 수 있으면 true로 판단해. 애매하면 true로 판단해.
        음식과 전혀 관련이 없는 이미지일 때만 false야.

        true로 판단하는 예시:
        - 실물 음식 사진 (먹기 전, 먹는 중, 조금 남은 것 모두)
        - 화면·인쇄물에 찍힌 음식 (스마트폰 화면, 메뉴판, 광고, 배달 앱 캡처)
        - 과자·음료·간편식 등의 포장지, 영양성분표, 성분 라벨
        - 음식 이름이나 먹은 내용을 적은 텍스트 (메모, 손글씨, 영수증 등)
        - 그림이나 일러스트라도 어떤 음식인지 알 수 있는 것

        false로 판단하는 예시:
        - 음식이 전혀 보이지 않는 사람, 동물, 풍경, 사물, 실내 사진
        - 음식과 관계없는 문서나 화면 캡처
        - 무엇을 찍었는지 알아볼 수 없는 사진 (완전히 검거나 심하게 흔들린 사진)

        [영양소 추정 방법]
        - 영양성분표나 포장지에 수치가 보이면 그 값을 우선해서 써. 1회 제공량과 총 내용량이 다르면 한 번에 다 먹는 제품은 총 내용량 기준으로 계산해.
        - 텍스트만 있으면 적힌 음식과 양을 보고 일반적인 1인분 기준으로 추정해.
        - 사진이면 보이는 양을 기준으로 추정해.

        [출력 형식]
        {
          "isFood": boolean,
          "rejectReason": string | null,   // isFood가 false일 때만, 왜 음식과 관련이 없다고 봤는지
          "name": string | null,            // 20자 이내 음식 이름
          "iconId": number | null,
          "calory": number | null,
          "carbs": number | null,
          "protein": number | null,
          "fat": number | null,
          "catComment": string | null
        }

        isFood가 false면 name 이하 필드는 모두 null로 채워.
        영양소 추정 시 calory ≈ carbs*4 + protein*4 + fat*9 를 만족시켜.
        catComment에는 고양이 말투로 100자 내외의 피드백을 써.
        1. 이 식사에서 많은 영양소와 부족한 영양소를 한 문장으로 짚어.
        2. 부족한 영양소를 채울 다음 식사 메뉴를 하나 추천해.
           - 집이나 식당에서 평소에 먹는 일반 메뉴 중에서 골라.
           - 이번 추천은 {cuisine} 메뉴 중에서, 가능하면 {dishForm} 형태로 골라.
           - 닭가슴살·샐러드 같은 다이어트 식단은 이 식사에 그 영양소가 정말 부족할 때만 추천해.
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
                "catComment" to mapOf("type" to "STRING"),
            ),
            "required" to
                listOf("isFood", "rejectReason", "name", "iconId", "calory", "carbs", "protein", "fat", "catComment"),
        )

        private val logger = LoggerFactory.getLogger(GeminiNutritionAnalysisClient::class.java)
    }

    override fun analyzeNutrition(imageKey: String): NutritionAnalysisResult {
        val objectContent = runBlocking { s3StorageClient.downloadObject(imageKey) }
        val encodedContent = Base64.encode(objectContent.bytes, 0, objectContent.bytes.size)
        val mimeType = objectContent.contentType

        val icons = iconService.getIcons()
        val iconsJson = objectMapper.writeValueAsString(icons)

        // 다음 식사 추천이 한 메뉴로 몰리지 않게 음식 종류와 메뉴 형태를 요청마다 무작위로 정한다.
        val promptWithRecommendation = PROMPT
            .replace("{cuisine}", RECOMMENDATION_CUISINES.random())
            .replace("{dishForm}", RECOMMENDATION_DISH_FORMS.random())
        val prompt = "$promptWithRecommendation 음식 아이콘 목록은 다음과 같아: $iconsJson 만약 뚜렷하게 맞는 것이 없으면 5(밥)를 골라줘."

        val requestBody = mapOf(
            "contents" to listOf(
                mapOf(
                    "role" to "user",
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
            .uri("/v1/publishers/google/models/{model}:generateContent", aiProperties.model)
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
            nutrition = Nutrition(parsed.calory, parsed.carbs, parsed.protein, parsed.fat),
            catComment = parsed.catComment,
            iconId = parsed.iconId,
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
    val catComment: String,
)

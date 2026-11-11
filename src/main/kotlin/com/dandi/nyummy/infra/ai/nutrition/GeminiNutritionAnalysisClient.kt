package com.dandi.nyummy.infra.ai.nutrition

import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import com.dandi.nyummy.infra.storage.s3.dto.S3ObjectContentResult
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.IconSummaryResult
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.NutritionAnalysisResult
import com.dandi.nyummy.meal.repository.IconRepository
import com.dandi.nyummy.meal.service.IconService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import kotlin.io.encoding.Base64

@Component
@ConditionalOnBooleanProperty("app.ai.enabled")
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

        // 한국어가 아닌 언어는 아래 한국어 지시를 그대로 두고, 출력 언어 지시를 맨 끝에 덧붙인다.
        // 한국어 요청은 덧붙이는 것이 없어 지금까지와 같은 프롬프트로 분석한다.
        // 메뉴 추천 후보는 그 언어를 쓰는 사람이 평소에 먹는 음식으로 바꾼다. 영어는 어느 나라 사용자인지 몰라 넓게 둔다.
        private val JA_OUTPUT_LANGUAGE_PROMPT = OutputLanguagePrompt(
            cuisines = listOf("和食", "中華", "洋食", "韓国料理", "エスニック"),
            dishForms = listOf("ご飯もの・丼", "麺類", "汁物・鍋", "定食", "炒め物"),
            instruction = """
        [출력 언어]
        rejectReason, name, catComment는 모두 일본어로 써. 한국어를 섞지 마.
        - name은 일본에서 흔히 부르는 음식 이름으로 써. (예: 김치찌개 → キムチチゲ)
        - catComment는 일본어 고양이 말투로 쓰고, 문장 끝을 "~にゃ"로 맺어. 추천하는 메뉴 이름도 일본어로 써.
    """,
        )
        private val EN_OUTPUT_LANGUAGE_PROMPT = OutputLanguagePrompt(
            cuisines = listOf("Korean", "Southeast Asian", "East Asian", "Western", "Mediterranean"),
            dishForms = listOf("rice bowl", "noodles", "soup or stew", "grilled plate", "stir-fry"),
            // 영어는 같은 내용이 한국어보다 길어 글자 수를 늘린다. name은 100자, cat_comment는 255자 컬럼이라 여유를 둔다.
            instruction = """
        [출력 언어]
        rejectReason, name, catComment는 모두 영어로 써. 한국어를 섞지 마.
        - name은 영어권에서 통하는 음식 이름으로, 위의 20자 대신 30자 이내로 써. (예: 김치찌개 → Kimchi stew)
        - catComment는 장난스러운 고양이 말투의 영어로 쓰고 "meow"를 자연스럽게 넣어. 위의 100자 대신 160자 이내로 써. 추천하는 메뉴 이름도 영어로 써.
    """,
        )

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

    /**
     * 식사 이미지를 Gemini로 분석한다.
     *
     * @throws AnalysisFailureException 실패를 [AnalysisFailureCategory]로 나눠 던진다. 분류에 없는 예외는 그대로 올린다.
     */
    override fun analyzeNutrition(imageKey: String, language: Language): NutritionAnalysisResult {
        val objectContent = downloadImage(imageKey)
        val encodedContent = Base64.encode(objectContent.bytes, 0, objectContent.bytes.size)
        val mimeType = objectContent.contentType

        val icons = getIcons()
        val iconsJson = objectMapper.writeValueAsString(icons)

        val prompt = createPrompt(language, iconsJson)

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

        val response = try {
            restClient.post()
                .uri("/v1/publishers/google/models/{model}:generateContent", aiProperties.model)
                .header("x-goog-api-key", aiProperties.apiKey)
                .body(requestBody)
                .retrieve()
                .body(GeminiGenerateContentResponse::class.java)
        } catch (e: HttpClientErrorException.TooManyRequests) {
            throw AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "Gemini 요청 한도를 넘었습니다.", e)
        } catch (e: HttpClientErrorException) {
            if (isMisconfigured(e)) {
                throw AnalysisFailureException(
                    AnalysisFailureCategory.MISCONFIGURED,
                    "Gemini 설정 오류로 요청이 거부됐습니다: ${e.statusCode}",
                    e,
                )
            }
            throw AnalysisFailureException(
                AnalysisFailureCategory.INVALID_REQUEST,
                "Gemini가 요청을 거부했습니다: ${e.statusCode}",
                e,
            )
        } catch (e: HttpServerErrorException) {
            throw AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "Gemini 서버 오류입니다: ${e.statusCode}", e)
        } catch (e: ResourceAccessException) {
            // 연결·읽기 타임아웃과 연결 풀 대기 시간 초과가 여기로 온다.
            throw AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "Gemini에 연결하지 못했습니다.", e)
        } catch (e: RestClientException) {
            // 위에서 잡지 않은 나머지는 응답을 GeminiGenerateContentResponse로 읽지 못한 경우다.
            throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 응답을 읽지 못했습니다.", e)
        } ?: throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 응답이 비어 있습니다.")

        val resultJson = response.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?: throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 응답에 결과가 없습니다.")

        val parsed = convertNutritionResponse(resultJson)

        if (!parsed.isFood) {
            logger.info("영양 분석에 실패했습니다: imageKey={}, rejectReason={}", imageKey, parsed.rejectReason)
            throw AnalysisFailureException(AnalysisFailureCategory.NOT_FOOD, "음식이 아닙니다.")
        }

        return NutritionAnalysisResult(
            name = parsed.name,
            nutrition = Nutrition(parsed.calory, parsed.carbs, parsed.protein, parsed.fat),
            catComment = parsed.catComment,
            iconId = parsed.iconId,
        )
    }

    /**
     * 분석 프롬프트. 한국어는 지금까지와 같고, 다른 언어는 메뉴 추천 후보를 바꾸고 출력 언어 지시를 맨 끝에 덧붙인다.
     * 다음 식사 추천이 한 메뉴로 몰리지 않게 음식 종류와 메뉴 형태를 요청마다 무작위로 정한다.
     */
    private fun createPrompt(language: Language, iconsJson: String): String {
        val outputLanguagePrompt = pickOutputLanguagePrompt(language)
        val promptWithRecommendation = PROMPT
            .replace("{cuisine}", outputLanguagePrompt.cuisines.random())
            .replace("{dishForm}", outputLanguagePrompt.dishForms.random())
        val prompt = "$promptWithRecommendation 음식 아이콘 목록은 다음과 같아: $iconsJson 만약 뚜렷하게 맞는 것이 없으면 5(밥)를 골라줘."

        return outputLanguagePrompt.instruction?.let { "$prompt\n$it" } ?: prompt
    }

    // when으로 고르면 언어를 추가할 때 여기서 컴파일이 깨져 빠뜨리지 않는다
    private fun pickOutputLanguagePrompt(language: Language): OutputLanguagePrompt = when (language) {
        Language.KO -> OutputLanguagePrompt(RECOMMENDATION_CUISINES, RECOMMENDATION_DISH_FORMS, instruction = null)
        Language.JA -> JA_OUTPUT_LANGUAGE_PROMPT
        Language.EN -> EN_OUTPUT_LANGUAGE_PROMPT
    }

    /**
     * Gemini가 요청 하나가 아니라 우리 쪽 설정(키·권한·모델 이름) 때문에 거부했는지.
     *
     * 401·403은 키·권한, 404는 모델 이름·경로 오류다. 400은 이미지 같은 요청 문제와 키 오류가 같은 코드로 오므로
     * 본문의 오류 이유(ErrorInfo reason)가 API_KEY_로 시작할 때만 설정 오류로 본다.
     */
    private fun isMisconfigured(e: HttpClientErrorException): Boolean = when (e.statusCode) {
        HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND -> true
        HttpStatus.BAD_REQUEST -> extractErrorReasons(e).any { it.startsWith("API_KEY_") }
        else -> false
    }

    /** Gemini 오류 본문에서 오류 이유들을 꺼낸다. 본문이 약속한 형식이 아니면 이유가 없는 것으로 본다. */
    private fun extractErrorReasons(e: HttpClientErrorException): List<String> = try {
        objectMapper.readValue(e.responseBodyAsString, GeminiErrorResponse::class.java)
            .error?.details.orEmpty()
            .mapNotNull { it.reason }
    } catch (_: JacksonException) {
        emptyList()
    }

    /**
     * 분석할 식사 이미지를 S3에서 받는다.
     * 객체가 없거나 비었으면 다시 해도 소용없으므로 [AnalysisFailureCategory.INVALID_REQUEST],
     * 권한 오류(403)는 이미지와 상관없이 모든 요청이 거부되므로 [AnalysisFailureCategory.MISCONFIGURED],
     * S3 서버 오류·요청 한도·네트워크 오류는 [AnalysisFailureCategory.UNAVAILABLE]이다.
     */
    private fun downloadImage(imageKey: String): S3ObjectContentResult = try {
        runBlocking { s3StorageClient.downloadObject(imageKey) }
    } catch (e: NoSuchKey) {
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_REQUEST, "분석할 이미지가 없습니다: $imageKey", e)
    } catch (e: ServiceException) {
        val metadata = e.sdkErrorMetadata
        val category = when {
            (metadata.protocolResponse as? HttpResponse)?.status?.value == HttpStatus.FORBIDDEN.value() ->
                AnalysisFailureCategory.MISCONFIGURED

            metadata.errorType == ServiceException.ErrorType.Client && !metadata.isThrottling ->
                AnalysisFailureCategory.INVALID_REQUEST

            else -> AnalysisFailureCategory.UNAVAILABLE
        }
        throw AnalysisFailureException(category, "분석할 이미지를 받지 못했습니다: $imageKey", e)
    } catch (e: SdkBaseException) {
        // 응답을 받기 전의 실패(타임아웃·연결 오류·재시도 소진)다.
        throw AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "분석할 이미지를 받지 못했습니다: $imageKey", e)
    } catch (e: IllegalStateException) {
        // S3StorageClient가 바디 없는 객체를 이렇게 알린다.
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_REQUEST, "분석할 이미지가 비어 있습니다: $imageKey", e)
    }

    /** 프롬프트에 넣을 아이콘 목록. DB 조회 실패는 잠시 뒤 다시 하면 될 수 있다. */
    private fun getIcons(): List<IconSummaryResult> = try {
        iconService.getIcons()
    } catch (e: DataAccessException) {
        throw AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "아이콘 목록을 조회하지 못했습니다.", e)
    }

    private fun convertNutritionResponse(resultJson: String): GeminiNutritionResponse = try {
        objectMapper.readValue(resultJson, GeminiNutritionResponse::class.java)
    } catch (e: JacksonException) {
        throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 결과를 해석하지 못했습니다.", e)
    }
}

data class GeminiGenerateContentResponse(val candidates: List<GeminiCandidate> = emptyList())
data class GeminiCandidate(val content: GeminiContent?)
data class GeminiContent(val parts: List<GeminiPart> = emptyList())
data class GeminiPart(val text: String?)
private data class GeminiErrorResponse(val error: GeminiError?)
private data class GeminiError(val details: List<GeminiErrorDetail> = emptyList())
private data class GeminiErrorDetail(val reason: String?)
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

/**
 * 언어마다 다른 분석 프롬프트 재료.
 *
 * @property instruction 프롬프트 맨 끝에 덧붙일 출력 언어 지시. 한국어는 null이라 기존 프롬프트 그대로다
 */
private data class OutputLanguagePrompt(
    val cuisines: List<String>,
    val dishForms: List<String>,
    val instruction: String?,
)

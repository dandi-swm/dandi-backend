package com.dandi.nyummy.infra.ai.report

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.nutrition.GeminiGenerateContentResponse
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import com.dandi.nyummy.report.dto.NutritionBalanceResult
import com.dandi.nyummy.report.dto.ReportTextResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

@Component
@ConditionalOnBooleanProperty("app.ai.enabled")
class GeminiReportGenerationClient(
    private val restClient: RestClient,
    private val aiProperties: AiProperties,
    private val objectMapper: ObjectMapper,
    private val messageSource: MessageSource,
) : ReportGenerationClient {

    companion object {
        // 길이는 프롬프트에 주는 목표(~_GUIDE_LENGTH)와 받은 문장을 거르는 상한(~_MAX_LENGTH)을 따로 둔다(2026-11-08 실측).
        // - 상한을 프롬프트 목표와 같게(조언 40·마무리 30) 두었을 때 30회 중 5회가 1~2자 넘겨 다시 만들었다.
        //   다시 만들기가 잦으면 리포트가 실패·환불로 끝날 수 있다.
        // - 프롬프트에 늘린 상한(조언 60·마무리 40)을 그대로 주면 Gemini가 그만큼 길게 써 조언 평균 30 → 46자, 마무리 최대가 40자로 상한에 붙었다.
        // 그래서 프롬프트에는 짧은 목표를 주고, 상한은 목표를 조금 넘긴 문장도 받아들이도록 여유를 둔다.
        const val TITLE_GUIDE_LENGTH = 25
        const val TITLE_MAX_LENGTH = 30
        const val ADVICE_GUIDE_LENGTH = 40
        const val ADVICE_MAX_LENGTH = 60
        const val SUGGESTION_COUNT = 3
        const val SUGGESTION_GUIDE_LENGTH = 45
        const val SUGGESTION_MAX_LENGTH = 60
        const val CLOSING_GUIDE_LENGTH = 30
        const val CLOSING_MAX_LENGTH = 40

        private val DIGIT = Regex("[0-9０-９]")

        private const val PROMPT =
            """
        너는 식사 기록 앱의 고양이 캐릭터야. 사용자의 한 주(일요일~토요일) 식사를 서버가 집계한 값을 보고 주간 리포트의 문장을 써.
        설명이나 마크다운 코드블록 없이 JSON 객체만 반환해.

        [집계 값]
        {nutritionBalance}

        집계 값에서 current는 기록한 날의 하루 평균, target은 하루 목표 섭취량이야.
        macroNutrients의 percentOfTarget은 양이 목표의 몇 %인지, caloryShare는 탄단지 칼로리 중 그 영양소가 차지한 비율(%),
        targetCaloryShare는 목표 비율(%)이야. days의 foods는 그날 먹은 음식 이름이야.

        [이번 주의 중심]
        {focus}

        [쓰는 규칙]
        - 숫자(아라비아 숫자)를 절대 쓰지 마. 칼로리·그램·퍼센트·횟수·날짜는 화면에 서버가 따로 보여줘.
          "많이", "조금", "자주", "며칠" 같은 말로 써.
        - 위의 '이번 주의 중심'을 주인공으로 써. 다른 영양소를 주인공으로 삼지 마.
        - 실제로 먹은 음식 이름을 근거로 들어도 좋아. 먹지 않은 음식을 먹었다고 쓰지 마.
        - 닭가슴살·샐러드 같은 다이어트 식단은 그 영양소가 정말 부족할 때만 권해.

        [출력 형식]
        {
          "title": string,          // {titleLength}자 이내, 이번 주를 한 줄로 요약. 예: "단백질만 챙기면 완벽했던 한 주"
          "advice": string,         // {adviceLength}자 이내, 이번 주의 중심에 대한 한 줄 조언. 해요체
          "suggestions": string[],  // 정확히 {suggestionCount}개, 각 {suggestionLength}자 이내. 다음 주에 해 볼 구체적인 행동. 해요체
          "closing": string         // {closingLength}자 이내, 고양이 말투로 사용자(집사)에게 건네는 마무리 한 줄
        }
    """

        private val RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "title" to mapOf("type" to "STRING"),
                "advice" to mapOf("type" to "STRING"),
                "suggestions" to mapOf("type" to "ARRAY", "items" to mapOf("type" to "STRING")),
                "closing" to mapOf("type" to "STRING"),
            ),
            "required" to listOf("title", "advice", "suggestions", "closing"),
        )
    }

    /**
     * 집계 값을 프롬프트에 넣어 Gemini로 리포트 문장을 받는다. 받은 문장이 길이·개수·숫자 금지 규칙을 어기면
     * [ReportGenerationFailureCategory.INVALID_RESPONSE]로 실패한다. 숫자 문장은 서버가 만들므로 AI 문장에는 숫자가 없어야 한다.
     */
    override fun generateReportText(nutritionBalance: NutritionBalanceResult): ReportTextResult {
        val prompt = PROMPT
            .replace("{nutritionBalance}", objectMapper.writeValueAsString(nutritionBalance))
            .replace("{focus}", createFocus(nutritionBalance))
            .replace("{titleLength}", TITLE_GUIDE_LENGTH.toString())
            .replace("{adviceLength}", ADVICE_GUIDE_LENGTH.toString())
            .replace("{suggestionCount}", SUGGESTION_COUNT.toString())
            .replace("{suggestionLength}", SUGGESTION_GUIDE_LENGTH.toString())
            .replace("{closingLength}", CLOSING_GUIDE_LENGTH.toString())

        val requestBody = mapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))),
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
            throw ReportGenerationFailureException(
                ReportGenerationFailureCategory.UNAVAILABLE,
                "Gemini 요청 한도를 넘었습니다.",
                e,
            )
        } catch (e: HttpClientErrorException) {
            val category = if (isMisconfigured(e)) {
                ReportGenerationFailureCategory.MISCONFIGURED
            } else {
                ReportGenerationFailureCategory.INVALID_REQUEST
            }
            throw ReportGenerationFailureException(category, "Gemini가 요청을 거부했습니다: ${e.statusCode}", e)
        } catch (e: HttpServerErrorException) {
            throw ReportGenerationFailureException(
                ReportGenerationFailureCategory.UNAVAILABLE,
                "Gemini 서버 오류입니다: ${e.statusCode}",
                e,
            )
        } catch (e: ResourceAccessException) {
            // 연결·읽기 타임아웃과 연결 풀 대기 시간 초과가 여기로 온다.
            throw ReportGenerationFailureException(
                ReportGenerationFailureCategory.UNAVAILABLE,
                "Gemini에 연결하지 못했습니다.",
                e,
            )
        } catch (e: RestClientException) {
            throw ReportGenerationFailureException(
                ReportGenerationFailureCategory.INVALID_RESPONSE,
                "Gemini 응답을 읽지 못했습니다.",
                e,
            )
        }
            ?: throw ReportGenerationFailureException(
                ReportGenerationFailureCategory.INVALID_RESPONSE,
                "Gemini 응답이 비어 있습니다.",
            )

        val resultJson = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw ReportGenerationFailureException(
                ReportGenerationFailureCategory.INVALID_RESPONSE,
                "Gemini 응답에 결과가 없습니다.",
            )

        return convertReportText(resultJson).also { validateReportText(it) }
    }

    /**
     * 이번 주에 무엇을 중심으로 쓸지. 서버가 고른 강조 영양소가 있으면 그것이 목표 비율보다 적었는지 많았는지를, 없으면(탄단지가 고르게
     * 균형을 이룬 주) 잘한 점을 중심으로 쓰게 한다. 제목·조언이 화면의 강조 표시와 어긋나지 않게 하기 위해서다.
     */
    private fun createFocus(nutritionBalance: NutritionBalanceResult): String {
        val focused = nutritionBalance.macroNutrients.firstOrNull { it.isFocused }
            ?: return "탄수화물·단백질·지방이 목표 비율에 가깝게 고르게 균형을 이뤘어. 잘한 점을 칭찬하고, 이 균형을 이어 갈 방법을 써."
        val direction = if (focused.caloryShare < focused.targetCaloryShare) "목표 비율보다 적었어" else "목표 비율보다 많았어"
        // 프롬프트의 지시는 한국어라 영양소 이름도 원본 언어로 넣는다
        val nutrientName = messageSource.getMessage(
            "macroNutrient.${focused.nutrient.name}",
            Language.ORIGINAL_LANGUAGE,
        )
        return "${nutrientName}이 칼로리 중 차지한 비율이 $direction. ${nutrientName}을 중심으로 써."
    }

    /** 401·403은 키·권한, 404는 모델 이름·경로 오류다. 400은 본문의 오류 이유가 API_KEY_로 시작할 때만 설정 오류로 본다. */
    private fun isMisconfigured(e: HttpClientErrorException): Boolean = when (e.statusCode) {
        HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND -> true

        HttpStatus.BAD_REQUEST -> try {
            objectMapper.readValue(e.responseBodyAsString, GeminiErrorResponse::class.java)
                .error?.details.orEmpty()
                .any { it.reason?.startsWith("API_KEY_") == true }
        } catch (_: JacksonException) {
            false
        }

        else -> false
    }

    private fun convertReportText(resultJson: String): ReportTextResult = try {
        objectMapper.readValue(resultJson, ReportTextResult::class.java)
    } catch (e: JacksonException) {
        throw ReportGenerationFailureException(
            ReportGenerationFailureCategory.INVALID_RESPONSE,
            "Gemini 결과를 해석하지 못했습니다.",
            e,
        )
    }

    private fun validateReportText(text: ReportTextResult) {
        val sentences = listOf(text.title, text.advice, text.closing) + text.suggestions
        val violation = when {
            text.title.isBlank() || text.title.length > TITLE_MAX_LENGTH -> "title 길이"
            text.advice.isBlank() || text.advice.length > ADVICE_MAX_LENGTH -> "advice 길이"
            text.closing.isBlank() || text.closing.length > CLOSING_MAX_LENGTH -> "closing 길이"
            text.suggestions.size != SUGGESTION_COUNT -> "suggestions 개수"
            text.suggestions.any { it.isBlank() || it.length > SUGGESTION_MAX_LENGTH } -> "suggestions 길이"
            sentences.any { DIGIT.containsMatchIn(it) } -> "숫자 포함"
            else -> null
        }
        if (violation != null) {
            throw ReportGenerationFailureException(
                ReportGenerationFailureCategory.INVALID_RESPONSE,
                "Gemini가 쓴 리포트 문장이 규칙을 어겼습니다: $violation",
            )
        }
    }
}

private data class GeminiErrorResponse(val error: GeminiError?)
private data class GeminiError(val details: List<GeminiErrorDetail> = emptyList())
private data class GeminiErrorDetail(val reason: String?)

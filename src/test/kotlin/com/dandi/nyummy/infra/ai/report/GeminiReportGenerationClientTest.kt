package com.dandi.nyummy.infra.ai.report

import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.language.createTestMessageSource
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.report.calculator.calculateNutritionBalance
import com.dandi.nyummy.report.dto.MacroNutrientBalanceResponse
import com.dandi.nyummy.report.enum.MacroNutrient
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Gemini 실패가 정한 [ReportGenerationFailureCategory]로 나뉘는지, 규칙을 어긴 문장을 INVALID_RESPONSE로 막는지 다룬다.
 */
class GeminiReportGenerationClientTest {

    private val aiProperties = AiProperties(
        apiKey = "test-api-key",
        model = "gemini-test",
        recognitionModel = "gemini-recognition-test",
        baseUrl = "https://gemini.test",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofSeconds(1),
        maxConnections = 1,
        connectionRequestTimeout = Duration.ofSeconds(1),
    )
    private val generateContentUrl = "https://gemini.test/v1/publishers/google/models/gemini-test:generateContent"

    private val restClientBuilder = RestClient.builder().baseUrl(aiProperties.baseUrl)
    private val server = MockRestServiceServer.bindTo(restClientBuilder).build()
    private val objectMapper = jacksonObjectMapper()

    private val messageSource = createTestMessageSource()

    private val client =
        GeminiReportGenerationClient(restClientBuilder.build(), aiProperties, objectMapper, messageSource)

    private val nutritionBalance = calculateNutritionBalance(
        meals = emptyList(),
        startDate = LocalDate.parse("2026-11-01"),
        target = Nutrition(2000, 250, 100, 66),
        zone = ZoneId.of("Asia/Seoul"),
        language = Language.KO,
        messageSource = messageSource,
    )

    private val validText = mapOf(
        "title" to "단백질만 챙기면 완벽했던 한 주",
        "advice" to "저녁에 달걀이나 두부를 곁들여 보세요",
        "suggestions" to listOf("아침에 우유 한 잔을 더해 보세요", "점심 반찬으로 생선을 골라 보세요", "간식은 견과류로 바꿔 보세요"),
        "closing" to "다음 주도 같이 먹자, 집사.",
    )

    private fun assertReportGenerationFailure(expected: ReportGenerationFailureCategory, block: () -> Unit) {
        val exception = assertFailsWith<ReportGenerationFailureException>(block = block)
        assertEquals(expected, exception.category)
    }

    private fun givenGeminiText(text: Map<String, Any>) {
        val body = objectMapper.writeValueAsString(
            mapOf(
                "candidates" to listOf(
                    mapOf(
                        "content" to mapOf("parts" to listOf(mapOf("text" to objectMapper.writeValueAsString(text)))),
                    ),
                ),
            ),
        )
        server.expect(requestTo(generateContentUrl))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun givenGeminiStatus(status: HttpStatus) {
        server.expect(requestTo(generateContentUrl)).andRespond(withStatus(status))
    }

    @Test
    fun `규칙을 지킨 문장이면 그대로 돌려준다`() {
        givenGeminiText(validText)

        val result = client.generateReportText(nutritionBalance, Language.KO)

        assertEquals("단백질만 챙기면 완벽했던 한 주", result.title)
        assertEquals(3, result.suggestions.size)
    }

    @Test
    fun `강조 영양소가 있으면 그 영양소가 목표 비율보다 많았는지 적었는지를 프롬프트의 중심으로 넘긴다`() {
        val fatFocused = nutritionBalance.copy(
            macroNutrients = listOf(
                MacroNutrientBalanceResponse(MacroNutrient.CARBS, 85, 250, 34, 27, 50, isFocused = false),
                MacroNutrientBalanceResponse(MacroNutrient.PROTEIN, 55, 100, 55, 17, 20, isFocused = false),
                MacroNutrientBalanceResponse(MacroNutrient.FAT, 80, 66, 121, 56, 30, isFocused = true),
            ),
            focusedNutrient = MacroNutrient.FAT,
        )
        server.expect(requestTo(generateContentUrl))
            .andExpect(content().string(containsString("지방이 칼로리 중 차지한 비율이 목표 비율보다 많았어")))
            .andRespond(withSuccess(convertGenerateContentBody(validText), MediaType.APPLICATION_JSON))

        client.generateReportText(fatFocused, Language.KO)

        server.verify()
    }

    @Test
    fun `한국어는 출력 언어 지시를 덧붙이지 않고 지금 길이를 프롬프트에 준다`() {
        server.expect(requestTo(generateContentUrl))
            .andExpect(content().string(not(containsString("[출력 언어]"))))
            .andExpect(content().string(containsString("${GeminiReportGenerationClient.TITLE_GUIDE_LENGTH}자 이내")))
            .andRespond(withSuccess(convertGenerateContentBody(validText), MediaType.APPLICATION_JSON))

        client.generateReportText(nutritionBalance, Language.KO)

        server.verify()
    }

    @Test
    fun `일본어는 한국어 지시 뒤에 일본어로 쓰라는 지시를 덧붙인다`() {
        server.expect(requestTo(generateContentUrl))
            .andExpect(content().string(containsString("title, advice, suggestions, closing은 모두 일본어로 써.")))
            .andExpect(content().string(containsString("${GeminiReportGenerationClient.TITLE_GUIDE_LENGTH}자 이내")))
            .andRespond(withSuccess(convertGenerateContentBody(validText), MediaType.APPLICATION_JSON))

        client.generateReportText(nutritionBalance, Language.JA)

        server.verify()
    }

    @Test
    fun `영어는 길이를 두 배로 주고 그만큼 긴 문장도 받는다`() {
        val englishTitle = "a".repeat(GeminiReportGenerationClient.TITLE_MAX_LENGTH * 2)
        server.expect(requestTo(generateContentUrl))
            .andExpect(content().string(containsString("title, advice, suggestions, closing은 모두 영어로 써.")))
            .andExpect(content().string(containsString("${GeminiReportGenerationClient.TITLE_GUIDE_LENGTH * 2}자 이내")))
            .andRespond(
                withSuccess(
                    convertGenerateContentBody(validText + ("title" to englishTitle)),
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(englishTitle, client.generateReportText(nutritionBalance, Language.EN).title)

        server.verify()
        server.reset()
        givenGeminiText(validText + ("title" to englishTitle))
        assertReportGenerationFailure(ReportGenerationFailureCategory.INVALID_RESPONSE) {
            client.generateReportText(nutritionBalance, Language.KO)
        }
    }

    @Test
    fun `강조 영양소가 없으면 균형을 칭찬하는 것을 프롬프트의 중심으로 넘긴다`() {
        server.expect(requestTo(generateContentUrl))
            .andExpect(content().string(containsString("고르게 균형을 이뤘어")))
            .andRespond(withSuccess(convertGenerateContentBody(validText), MediaType.APPLICATION_JSON))

        client.generateReportText(nutritionBalance, Language.KO)

        server.verify()
    }

    private fun convertGenerateContentBody(text: Map<String, Any>): String = objectMapper.writeValueAsString(
        mapOf(
            "candidates" to listOf(
                mapOf("content" to mapOf("parts" to listOf(mapOf("text" to objectMapper.writeValueAsString(text))))),
            ),
        ),
    )

    @Test
    fun `문장에 숫자가 있으면 INVALID_RESPONSE로 실패한다`() {
        givenGeminiText(validText + ("advice" to "단백질을 20g 더 드세요"))

        assertReportGenerationFailure(ReportGenerationFailureCategory.INVALID_RESPONSE) {
            client.generateReportText(nutritionBalance, Language.KO)
        }
    }

    @Test
    fun `제안이 3개가 아니거나 제목이 길면 INVALID_RESPONSE로 실패한다`() {
        givenGeminiText(validText + ("suggestions" to listOf("하나", "둘")))
        assertReportGenerationFailure(ReportGenerationFailureCategory.INVALID_RESPONSE) {
            client.generateReportText(nutritionBalance, Language.KO)
        }

        server.reset()
        givenGeminiText(validText + ("title" to "가".repeat(GeminiReportGenerationClient.TITLE_MAX_LENGTH + 1)))
        assertReportGenerationFailure(ReportGenerationFailureCategory.INVALID_RESPONSE) {
            client.generateReportText(nutritionBalance, Language.KO)
        }
    }

    @Test
    fun `결과 JSON이 깨졌으면 INVALID_RESPONSE로 실패한다`() {
        val body = objectMapper.writeValueAsString(
            mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to "{not json")))))),
        )
        server.expect(requestTo(generateContentUrl)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

        assertReportGenerationFailure(ReportGenerationFailureCategory.INVALID_RESPONSE) {
            client.generateReportText(nutritionBalance, Language.KO)
        }
    }

    @Test
    fun `요청 한도 초과나 서버 오류면 UNAVAILABLE로 실패한다`() {
        listOf(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE).forEach {
            server.reset()
            givenGeminiStatus(it)

            assertReportGenerationFailure(ReportGenerationFailureCategory.UNAVAILABLE) {
                client.generateReportText(nutritionBalance, Language.KO)
            }
        }
    }

    @Test
    fun `키·권한·모델 이름 때문에 거부되면 MISCONFIGURED, 그 밖의 4xx면 INVALID_REQUEST로 실패한다`() {
        givenGeminiStatus(HttpStatus.FORBIDDEN)
        assertReportGenerationFailure(ReportGenerationFailureCategory.MISCONFIGURED) {
            client.generateReportText(nutritionBalance, Language.KO)
        }

        server.reset()
        givenGeminiStatus(HttpStatus.BAD_REQUEST)
        assertReportGenerationFailure(ReportGenerationFailureCategory.INVALID_REQUEST) {
            client.generateReportText(nutritionBalance, Language.KO)
        }
    }
}

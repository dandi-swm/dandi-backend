package com.dandi.nyummy.infra.ai.nutrition

import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.S3ErrorMetadata
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.storage.s3.S3StorageClient
import com.dandi.nyummy.infra.storage.s3.dto.S3ObjectContentResult
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.IconSummaryResult
import com.dandi.nyummy.meal.service.IconService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Gemini·S3·아이콘 조회의 실패가 정한 [AnalysisFailureCategory]로 나뉘는지 다룬다.
 * Gemini HTTP는 [MockRestServiceServer]로, S3·아이콘 조회는 mockk로 흉내 낸다.
 */
class GeminiNutritionAnalysisClientTest {

    private val imageKey = "meals/1/2026/11/02/uuid.jpg"
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
    private val s3StorageClient = mockk<S3StorageClient>()
    private val iconService = mockk<IconService>()
    private val objectMapper = jacksonObjectMapper()

    private val client = GeminiNutritionAnalysisClient(
        restClient = restClientBuilder.build(),
        aiProperties = aiProperties,
        s3StorageClient = s3StorageClient,
        objectMapper = objectMapper,
        iconService = iconService,
    )

    init {
        coEvery { s3StorageClient.downloadObject(imageKey) } returns
            S3ObjectContentResult(bytes = byteArrayOf(1, 2, 3), contentType = "image/jpeg")
        every { iconService.getIcons() } returns listOf(IconSummaryResult(id = 5L, name = "밥"))
    }

    private fun assertAnalysisFailure(expected: AnalysisFailureCategory, block: () -> Unit) {
        val exception = assertFailsWith<AnalysisFailureException>(block = block)
        assertEquals(expected, exception.category)
    }

    /** Gemini generateContent 응답 바디. 모델이 낸 결과는 candidates[0].content.parts[0].text에 문자열로 들어간다. */
    private fun convertGenerateContentBody(resultText: String): String = objectMapper.writeValueAsString(
        mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to resultText)))))),
    )

    private fun convertResultText(isFood: Boolean): String = objectMapper.writeValueAsString(
        mapOf(
            "isFood" to isFood,
            "rejectReason" to if (isFood) "" else "풍경 사진",
            "name" to if (isFood) "김치찌개" else "",
            "iconId" to if (isFood) 12 else 0,
            "calory" to if (isFood) 450 else 0,
            "carbs" to if (isFood) 40 else 0,
            "protein" to if (isFood) 25 else 0,
            "fat" to if (isFood) 20 else 0,
            "catComment" to if (isFood) "단백질이 든든하다냥" else "",
        ),
    )

    private fun givenGeminiSuccess(body: String) {
        server.expect(requestTo(generateContentUrl))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun givenGeminiStatus(status: HttpStatus) {
        server.expect(requestTo(generateContentUrl)).andRespond(withStatus(status))
    }

    /** Gemini 오류 바디. 오류 이유는 error.details[].reason(google.rpc.ErrorInfo)에 들어간다. */
    private fun givenGeminiError(status: HttpStatus, reason: String) {
        val body = objectMapper.writeValueAsString(
            mapOf("error" to mapOf("code" to status.value(), "details" to listOf(mapOf("reason" to reason)))),
        )
        server.expect(requestTo(generateContentUrl))
            .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body))
    }

    private fun givenS3Failure(exception: Throwable) {
        coEvery { s3StorageClient.downloadObject(imageKey) } throws exception
    }

    /** 오류 메타데이터를 채우는 SDK API는 내부용이라, 분류에 쓰는 값만 mockk로 흉내 낸다. */
    private fun createS3Exception(
        errorType: ServiceException.ErrorType,
        status: HttpStatusCode,
        isThrottling: Boolean = false,
    ): S3Exception {
        val response = mockk<HttpResponse> { every { this@mockk.status } returns status }
        val metadata = mockk<S3ErrorMetadata> {
            every { this@mockk.errorType } returns errorType
            every { this@mockk.isThrottling } returns isThrottling
            every { protocolResponse } returns response
        }
        return mockk<S3Exception> { every { sdkErrorMetadata } returns metadata }
    }

    // 성공

    @Test
    fun `음식이면 이름과 영양, 아이콘, 고양이 한마디를 돌려준다`() {
        server.expect(requestTo(generateContentUrl))
            .andExpect(header("x-goog-api-key", "test-api-key"))
            .andRespond(
                withSuccess(convertGenerateContentBody(convertResultText(isFood = true)), MediaType.APPLICATION_JSON),
            )

        val result = client.analyzeNutrition(imageKey, Language.KO)

        assertEquals("김치찌개", result.name)
        assertEquals(450, result.nutrition.calory)
        assertEquals(40, result.nutrition.carbs)
        assertEquals(25, result.nutrition.protein)
        assertEquals(20, result.nutrition.fat)
        assertEquals(12L, result.iconId)
        assertEquals("단백질이 든든하다냥", result.catComment)
        server.verify()
    }

    // 프롬프트

    /** 성공 응답을 주면서 요청에 실린 프롬프트(contents[0].parts[1].text)를 꺼낸다. */
    private fun capturePrompt(language: Language): String {
        var requestBody = ""
        server.expect(requestTo(generateContentUrl))
            .andExpect { requestBody = (it as MockClientHttpRequest).bodyAsString }
            .andRespond(
                withSuccess(convertGenerateContentBody(convertResultText(isFood = true)), MediaType.APPLICATION_JSON),
            )

        client.analyzeNutrition(imageKey, language)

        return objectMapper.readTree(requestBody)["contents"][0]["parts"][1]["text"].asString()
    }

    // 추천 음식 종류는 요청마다 무작위라, 고른 값을 꺼내 후보 안에 있는지 본다
    private fun extractRecommendationCuisine(prompt: String): String =
        assertNotNull(Regex("이번 추천은 (.+?) 메뉴 중에서 골라").find(prompt)).groupValues[1]

    @Test
    fun `한국어는 출력 언어 지시를 덧붙이지 않고 한국 메뉴 중에서 추천하게 한다`() {
        val prompt = capturePrompt(Language.KO)

        assertFalse(prompt.contains("[출력 언어]"))
        assertTrue(prompt.endsWith("만약 뚜렷하게 맞는 것이 없으면 5(밥)를 골라줘."))
        assertTrue(extractRecommendationCuisine(prompt) in setOf("한식", "중식", "일식", "양식"))
    }

    @Test
    fun `메뉴 추천은 대중적이고 더 건강한 메뉴로 고르게 하고 메뉴 형태는 정하지 않는다`() {
        val prompt = capturePrompt(Language.KO)

        assertTrue(prompt.contains("누구나 아는 메뉴 이름으로 써."))
        assertTrue(prompt.contains("같은 조건이면 더 건강한 메뉴를 골라."))
        assertTrue(prompt.contains("닭가슴살·샐러드만 먹는 다이어트 식단은 이 식사에 그 영양소가 정말 부족할 때만 추천해."))
        assertFalse(prompt.contains("{dishForm}"))
        assertFalse(prompt.contains("형태로 골라"))
    }

    @Test
    fun `일본어는 한국어 지시 뒤에 일본어로 쓰라는 지시를 붙이고 일본 메뉴 중에서 추천하게 한다`() {
        val prompt = capturePrompt(Language.JA)

        assertTrue(prompt.contains("[isFood 판단 기준]"), "한국어 지시는 그대로 둔다")
        assertTrue(prompt.trimEnd().endsWith("추천하는 메뉴 이름도 일본어로 써."))
        assertTrue(prompt.contains("rejectReason, name, catComment는 모두 일본어로 써."))
        assertTrue(extractRecommendationCuisine(prompt) in setOf("和食", "中華", "洋食", "韓国料理", "定食"))
    }

    @Test
    fun `영어는 이름과 고양이 코멘트 길이를 늘려 영어로 쓰게 한다`() {
        val prompt = capturePrompt(Language.EN)

        assertTrue(prompt.contains("rejectReason, name, catComment는 모두 영어로 써."))
        assertTrue(prompt.contains("위의 20자 대신 30자 이내로 써."))
        assertTrue(prompt.contains("위의 100자 대신 160자 이내로 써."))
        assertTrue(
            extractRecommendationCuisine(prompt) in
                setOf("Korean", "Southeast Asian", "East Asian", "Western", "Mediterranean"),
        )
    }

    // Gemini 응답 내용

    @Test
    fun `음식이 아니면 NOT_FOOD로 실패한다`() {
        givenGeminiSuccess(convertGenerateContentBody(convertResultText(isFood = false)))

        assertAnalysisFailure(AnalysisFailureCategory.NOT_FOOD) { client.analyzeNutrition(imageKey, Language.KO) }
    }

    @Test
    fun `결과 JSON이 깨졌으면 INVALID_RESPONSE로 실패한다`() {
        givenGeminiSuccess(convertGenerateContentBody("{\"isFood\": tr"))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            client.analyzeNutrition(imageKey, Language.KO)
        }
    }

    @Test
    fun `결과가 없는 응답이면 INVALID_RESPONSE로 실패한다`() {
        givenGeminiSuccess("""{"candidates": []}""")

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            client.analyzeNutrition(imageKey, Language.KO)
        }
    }

    @Test
    fun `응답 본문을 읽지 못하면 INVALID_RESPONSE로 실패한다`() {
        givenGeminiSuccess("<html>not json</html>")

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_RESPONSE) {
            client.analyzeNutrition(imageKey, Language.KO)
        }
    }

    // Gemini HTTP 오류

    @Test
    fun `요청 한도를 넘으면 UNAVAILABLE로 실패한다`() {
        givenGeminiStatus(HttpStatus.TOO_MANY_REQUESTS)

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { client.analyzeNutrition(imageKey, Language.KO) }
    }

    @Test
    fun `Gemini 서버 오류면 UNAVAILABLE로 실패한다`() {
        listOf(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE).forEach { status ->
            server.reset()
            givenGeminiStatus(status)

            assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) {
                client.analyzeNutrition(imageKey, Language.KO)
            }
        }
    }

    @Test
    fun `Gemini가 요청을 거부하면 INVALID_REQUEST로 실패한다`() {
        givenGeminiStatus(HttpStatus.BAD_REQUEST)

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_REQUEST) {
            client.analyzeNutrition(imageKey, Language.KO)
        }
    }

    @Test
    fun `Gemini가 키·권한·모델 이름 때문에 거부하면 MISCONFIGURED로 실패한다`() {
        listOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND).forEach { status ->
            server.reset()
            givenGeminiStatus(status)

            assertAnalysisFailure(AnalysisFailureCategory.MISCONFIGURED) {
                client.analyzeNutrition(imageKey, Language.KO)
            }
        }
    }

    @Test
    fun `Gemini가 400을 줘도 API 키 오류면 MISCONFIGURED로 실패한다`() {
        givenGeminiError(HttpStatus.BAD_REQUEST, reason = "API_KEY_INVALID")

        assertAnalysisFailure(AnalysisFailureCategory.MISCONFIGURED) { client.analyzeNutrition(imageKey, Language.KO) }
    }

    @Test
    fun `Gemini가 400을 주고 API 키 오류가 아니면 INVALID_REQUEST로 실패한다`() {
        givenGeminiError(HttpStatus.BAD_REQUEST, reason = "INVALID_ARGUMENT")

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_REQUEST) {
            client.analyzeNutrition(imageKey, Language.KO)
        }
    }

    @Test
    fun `Gemini에 연결하지 못하면 UNAVAILABLE로 실패한다`() {
        server.expect(requestTo(generateContentUrl)).andRespond(withException(IOException("connect timed out")))

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { client.analyzeNutrition(imageKey, Language.KO) }
    }

    // S3 이미지 다운로드

    @Test
    fun `분석할 이미지가 없으면 INVALID_REQUEST로 실패하고 Gemini를 부르지 않는다`() {
        givenS3Failure(NoSuchKey {})

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_REQUEST) {
            client.analyzeNutrition(imageKey, Language.KO)
        }

        server.verify()
    }

    @Test
    fun `S3 서버 오류면 UNAVAILABLE로 실패한다`() {
        givenS3Failure(createS3Exception(ServiceException.ErrorType.Server, HttpStatusCode.InternalServerError))

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { client.analyzeNutrition(imageKey, Language.KO) }
    }

    @Test
    fun `S3 요청 한도를 넘으면 UNAVAILABLE로 실패한다`() {
        givenS3Failure(
            createS3Exception(
                ServiceException.ErrorType.Client,
                HttpStatusCode.ServiceUnavailable,
                isThrottling = true,
            ),
        )

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { client.analyzeNutrition(imageKey, Language.KO) }
    }

    @Test
    fun `S3가 요청을 거부하면 INVALID_REQUEST로 실패한다`() {
        givenS3Failure(createS3Exception(ServiceException.ErrorType.Client, HttpStatusCode.BadRequest))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_REQUEST) {
            client.analyzeNutrition(imageKey, Language.KO)
        }
    }

    @Test
    fun `S3 권한이 없어 거부하면 MISCONFIGURED로 실패한다`() {
        givenS3Failure(createS3Exception(ServiceException.ErrorType.Client, HttpStatusCode.Forbidden))

        assertAnalysisFailure(AnalysisFailureCategory.MISCONFIGURED) { client.analyzeNutrition(imageKey, Language.KO) }
    }

    @Test
    fun `S3 응답을 받기 전에 실패하면 UNAVAILABLE로 실패한다`() {
        givenS3Failure(SdkBaseException("connect timed out"))

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { client.analyzeNutrition(imageKey, Language.KO) }
    }

    @Test
    fun `이미지가 비었으면 INVALID_REQUEST로 실패한다`() {
        givenS3Failure(IllegalStateException("S3 객체 바디가 비어 있습니다: $imageKey"))

        assertAnalysisFailure(AnalysisFailureCategory.INVALID_REQUEST) {
            client.analyzeNutrition(imageKey, Language.KO)
        }
    }

    // 아이콘 조회

    @Test
    fun `아이콘 목록을 조회하지 못하면 UNAVAILABLE로 실패한다`() {
        every { iconService.getIcons() } throws QueryTimeoutException("timeout")

        assertAnalysisFailure(AnalysisFailureCategory.UNAVAILABLE) { client.analyzeNutrition(imageKey, Language.KO) }
    }
}

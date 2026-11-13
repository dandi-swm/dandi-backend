package com.dandi.nyummy.infra.ai

import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.infra.ai.nutrition.GeminiGenerateContentResponse
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.springframework.http.HttpStatus
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import kotlin.io.encoding.Base64

/**
 * Gemini generateContent 호출 한 번. 식사 인식 v2의 클라이언트(음식 인식·1인분 조회·고양이 코멘트)가 함께 쓴다.
 *
 * 결과는 응답 스키마에 맞춘 JSON 문자열로 받고, Gemini의 오류(HTTP 상태·응답 형식)는 [AnalysisFailureCategory]로 번역한다.
 * v1 분석과 리포트 클라이언트는 같은 호출을 각자 갖고 있다.
 */
@Component
class GeminiClient(
    private val restClient: RestClient,
    private val httpClient: CloseableHttpClient,
    private val aiProperties: AiProperties,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        // 식사 인식 v2의 호출은 모두 thinking을 가장 낮게 둔다. 응답이 가장 빠르다.
        private const val THINKING_LEVEL = "MINIMAL"
    }

    /**
     * @param model 부를 모델 이름
     * @param parts 요청 내용. [createGeminiImagePart]·[createGeminiTextPart]로 만든다
     * @param responseSchema 결과 JSON의 스키마
     * @param timeout 응답을 기다릴 최대 시간. null이면 app.ai.read-timeout을 쓴다.
     *   다음 바이트를 기다리는 시간이라 호출 전체의 상한은 아니다. 전체 상한은 부르는 쪽이 따로 건다
     * @return 모델이 낸 결과 JSON 문자열
     * @throws AnalysisFailureException 실패를 [AnalysisFailureCategory]로 나눠 던진다
     */
    fun createContent(
        model: String,
        parts: List<Map<String, Any>>,
        responseSchema: Map<String, Any>,
        timeout: Duration? = null,
    ): String {
        val requestBody = mapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to parts)),
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "responseSchema" to responseSchema,
                "thinkingConfig" to mapOf("thinkingLevel" to THINKING_LEVEL),
            ),
        )

        val response = try {
            createRestClient(timeout).post()
                .uri("/v1/publishers/google/models/{model}:generateContent", model)
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
            // 연결·응답 대기 시간 초과와 연결 풀 대기 시간 초과가 여기로 온다.
            throw AnalysisFailureException(AnalysisFailureCategory.UNAVAILABLE, "Gemini에 연결하지 못했습니다.", e)
        } catch (e: RestClientException) {
            // 위에서 잡지 않은 나머지는 응답을 GeminiGenerateContentResponse로 읽지 못한 경우다.
            throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 응답을 읽지 못했습니다.", e)
        } ?: throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 응답이 비어 있습니다.")

        return response.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?: throw AnalysisFailureException(AnalysisFailureCategory.INVALID_RESPONSE, "Gemini 응답에 결과가 없습니다.")
    }

    /**
     * [timeout]이 있으면 응답 대기 시간을 그만큼으로 줄인 RestClient를 만든다.
     *
     * 응답 대기 시간은 요청 팩토리에 걸리는 값이라, 요청마다 다르게 주려면 그 값을 가진 팩토리가 따로 있어야 한다.
     * 팩토리는 연결 풀을 가진 HttpClient를 감싸기만 하므로 호출마다 만들어도 연결은 그대로 재사용한다.
     * 연결 풀에서 연결을 기다리는 시간도 [timeout]을 넘지 않게 한다.
     */
    private fun createRestClient(timeout: Duration?): RestClient {
        if (timeout == null) {
            return restClient
        }
        // 0은 HttpClient에서 "제한 없음"이라, 줄이려던 호출이 오히려 끝없이 기다리게 된다.
        require(timeout.toMillis() > 0) { "timeout은 1ms 이상이어야 합니다: $timeout" }

        val requestFactory = HttpComponentsClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(timeout)
            setConnectionRequestTimeout(minOf(timeout, aiProperties.connectionRequestTimeout))
        }
        return restClient.mutate().requestFactory(requestFactory).build()
    }

    /**
     * Gemini가 요청 하나가 아니라 우리 쪽 설정(키·권한·모델 이름) 때문에 거부했는지.
     *
     * 401·403은 키·권한, 404는 모델 이름·경로 오류다. 400은 요청 문제와 키 오류가 같은 코드로 오므로
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
}

/** 요청에 넣을 글 한 덩어리. */
fun createGeminiTextPart(text: String): Map<String, Any> = mapOf("text" to text)

/** 요청에 넣을 사진 한 장. */
fun createGeminiImagePart(imageBytes: ByteArray, contentType: String): Map<String, Any> = mapOf(
    "inlineData" to mapOf(
        "mimeType" to contentType,
        "data" to Base64.encode(imageBytes, 0, imageBytes.size),
    ),
)

private data class GeminiErrorResponse(val error: GeminiError?)
private data class GeminiError(val details: List<GeminiErrorDetail> = emptyList())
private data class GeminiErrorDetail(val reason: String?)

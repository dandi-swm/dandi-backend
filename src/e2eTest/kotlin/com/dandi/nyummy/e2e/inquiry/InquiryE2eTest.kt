package com.dandi.nyummy.e2e.inquiry

import com.dandi.nyummy.e2e.support.AuthFixture
import com.dandi.nyummy.e2e.support.RequestSpecs
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

class InquiryE2eTest {
    @Test
    fun `다른 사용자의 문의는 조회·수정·삭제·복구할 수 없다`() {
        // 생성 응답에 ID가 없어 목록에서 제목으로 찾는다. 이전 실행이 남긴 문의와 섞이지 않게 제목을 매번 다르게 만든다.
        val title = "E2E 소유권 확인 ${UUID.randomUUID()}"
        createInquiry(title)
        val inquiryId = getInquiryId(title)
        val otherSpec = RequestSpecs.createAuthorizedSpec(AuthFixture.otherAccessToken)

        try {
            Given { spec(otherSpec) } When { get("/api/v1/inquiries/$inquiryId") } Then { validateForbidden() }
            Given {
                spec(otherSpec)
                body(createInquiryBody("남의 문의 수정 시도"))
            } When {
                put("/api/v1/inquiries/$inquiryId")
            } Then {
                validateForbidden()
            }
            Given { spec(otherSpec) } When { delete("/api/v1/inquiries/$inquiryId") } Then { validateForbidden() }
            Given { spec(otherSpec) } When { post("/api/v1/inquiries/$inquiryId/restore") } Then { validateForbidden() }

            // 거부된 요청이 실제로 아무것도 바꾸지 않았는지 소유자로 확인한다(삭제되지 않았고 제목도 그대로).
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/inquiries/$inquiryId")
            } Then {
                statusCode(200)
                body("questionTitle", equalTo(title))
            }
        } finally {
            // 고정 계정에 테스트 문의가 쌓이지 않게 지운다.
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                delete("/api/v1/inquiries/$inquiryId")
            } Then {
                statusCode(204)
            }
        }
    }

    private fun createInquiry(title: String) {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(createInquiryBody(title))
        } When {
            post("/api/v1/inquiries")
        } Then {
            statusCode(201)
        }
    }

    private fun getInquiryId(title: String): Long {
        val inquiries = Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/inquiries")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath()
        }
        // JSON 숫자는 크기에 따라 Integer나 Long으로 읽히므로 Number로 받아 변환한다.
        val inquiryId = inquiries.param("title", title)
            .get<Number?>("inquiries.find { it.questionTitle == title }.inquiryId")
        return requireNotNull(inquiryId) { "방금 만든 문의를 목록에서 찾지 못했습니다: $title" }.toLong()
    }

    private fun createInquiryBody(title: String): Map<String, String> = mapOf(
        "category" to "QUESTION",
        "questionTitle" to title,
        "questionContent" to "E2E 테스트가 만든 문의입니다. 테스트가 끝나면 삭제됩니다.",
    )

    private fun ValidatableResponse.validateForbidden() {
        statusCode(403)
        body("code", equalTo("api.auth.forbidden"))
    }
}

package com.dandi.nyummy.e2e.inquiry

import com.dandi.nyummy.e2e.support.AuthFixture
import com.dandi.nyummy.e2e.support.ImageFixture
import com.dandi.nyummy.e2e.support.RequestSpecs
import com.dandi.nyummy.e2e.support.Smoke
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import io.restassured.specification.RequestSpecification
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertNull

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

    // 조회만 하므로 prod에서도 돌린다.
    @Smoke
    @Test
    fun `문의 목록을 조회한다`() {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
        } When {
            get("/api/v1/inquiries")
        } Then {
            statusCode(200)
            body("keySet()", hasItems("inquiries", "nextCursor", "hasNext"))
        }
    }

    @Test
    fun `이미지를 첨부한 문의를 만들고 수정·삭제·복구한다`() {
        val title = "E2E 문의 ${UUID.randomUUID()}"
        val imageKey = ImageFixture.createUploadedImage("INQUIRY", "/images/inquiry.png", "image/png")
        createInquiry(title, imageKey)
        val inquiryId = getInquiryId(title)

        try {
            // 목록 항목: 앱 목록 화면이 읽는 필드
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/inquiries")
            } Then {
                statusCode(200)
                body(
                    "inquiries.find { it.inquiryId == $inquiryId }.keySet()",
                    hasItems("inquiryId", "category", "questionTitle", "createdAt", "isAnswered", "hasUnreadAnswer"),
                )
            }

            // 상세: 첨부 이미지는 조회용 URL로 내려온다
            val detail = Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/inquiries/$inquiryId")
            } Then {
                statusCode(200)
                body(
                    "keySet()",
                    hasItems(
                        "inquiryId", "category", "questionTitle", "questionContent", "imageUrl",
                        "createdAt", "isAnswered", "answerTitle", "answerContent", "answeredAt",
                    ),
                )
                body("questionTitle", equalTo(title))
                body("isAnswered", equalTo(false))
                body("imageUrl", notNullValue())
            } Extract {
                jsonPath()
            }

            // 앱은 imageUrl로 바로 이미지를 받는다. 업로드 확정(temp → 최종)이 실패했으면 여기서 깨진다.
            Given {
                urlEncodingEnabled(false)
            } When {
                get(detail.getString("imageUrl"))
            } Then {
                statusCode(200)
                contentType(startsWith("image/png"))
            }

            // 수정: 제목을 바꾸고 imageKey를 null로 보내 이미지를 지운다. 수정된 문의가 응답으로 온다.
            val updatedTitle = "$title (수정)"
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
                body(createInquiryBody(updatedTitle, imageKey = null))
            } When {
                put("/api/v1/inquiries/$inquiryId")
            } Then {
                statusCode(200)
                body("questionTitle", equalTo(updatedTitle))
                body("imageUrl", nullValue())
            }

            // 삭제: 상세는 404, 목록에서도 빠진다
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                delete("/api/v1/inquiries/$inquiryId")
            } Then {
                statusCode(204)
            }
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/inquiries/$inquiryId")
            } Then {
                statusCode(404)
                body("code", equalTo("api.inquiry.notFound"))
            }
            assertNull(findInquiryId(updatedTitle), "삭제한 문의가 목록에 남아 있습니다")

            // 복구: 수정한 내용 그대로 다시 보인다
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                post("/api/v1/inquiries/$inquiryId/restore")
            } Then {
                statusCode(204)
            }
            Given {
                spec(RequestSpecs.createAuthorizedSpec())
            } When {
                get("/api/v1/inquiries/$inquiryId")
            } Then {
                statusCode(200)
                body("questionTitle", equalTo(updatedTitle))
            }
        } finally {
            // 중간에 실패하면 이미 삭제된 상태일 수 있다(그때는 404). 정리는 결과를 검증하지 않는다.
            Given { spec(RequestSpecs.createAuthorizedSpec()) } When { delete("/api/v1/inquiries/$inquiryId") }
        }
    }

    // 업로드 URL은 신고한 형식만 보고 발급한다. 확정할 때 실제 내용을 다시 검사해야 속인 파일을 막는다.
    @Test
    fun `확장자와 실제 내용이 다른 이미지는 첨부할 수 없다`() {
        val title = "E2E 형식 위조 ${UUID.randomUUID()}"
        // PNG 파일을 JPEG라고 신고해 .jpg 키로 올린다. S3는 내용을 보지 않으므로 업로드는 성공한다.
        val imageKey = ImageFixture.createUploadedImage("INQUIRY", "/images/inquiry.png", "image/jpeg")

        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(createInquiryBody(title, imageKey))
        } When {
            post("/api/v1/inquiries")
        } Then {
            statusCode(400)
            body("code", equalTo("api.s3.unsupportedContentType"))
        }

        assertNull(findInquiryId(title), "거부된 문의가 만들어졌습니다")
    }

    // imageKey는 응답으로 노출되는 값이라 다른 사용자가 알아낼 수 있다. 확정할 때 경로의 사용자를 확인해야 한다.
    @Test
    fun `다른 사용자가 올린 이미지는 첨부할 수 없다`() {
        val title = "E2E 남의 이미지 ${UUID.randomUUID()}"
        val imageKey = ImageFixture.createUploadedImage("INQUIRY", "/images/inquiry.png", "image/png")
        val otherSpec = RequestSpecs.createAuthorizedSpec(AuthFixture.otherAccessToken)

        Given {
            spec(otherSpec)
            body(createInquiryBody(title, imageKey))
        } When {
            post("/api/v1/inquiries")
        } Then {
            statusCode(400)
            body("code", equalTo("api.s3.invalidKey"))
        }

        assertNull(findInquiryId(title, otherSpec), "거부된 문의가 다른 사용자에게 만들어졌습니다")
    }

    private fun createInquiry(title: String, imageKey: String? = null) {
        Given {
            spec(RequestSpecs.createAuthorizedSpec())
            body(createInquiryBody(title, imageKey))
        } When {
            post("/api/v1/inquiries")
        } Then {
            statusCode(201)
        }
    }

    // imageKey가 null이면 이미지 없음. 수정 API에서는 null이 기존 이미지 삭제를 뜻한다.
    private fun createInquiryBody(title: String, imageKey: String? = null): Map<String, String?> = mapOf(
        "category" to "QUESTION",
        "questionTitle" to title,
        "questionContent" to "E2E 테스트가 만든 문의입니다. 테스트가 끝나면 삭제됩니다.",
        "imageKey" to imageKey,
    )

    private fun getInquiryId(title: String): Long =
        requireNotNull(findInquiryId(title)) { "방금 만든 문의를 목록에서 찾지 못했습니다: $title" }

    private fun findInquiryId(title: String, spec: RequestSpecification = RequestSpecs.createAuthorizedSpec()): Long? {
        val inquiries = Given {
            spec(spec)
        } When {
            get("/api/v1/inquiries")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath()
        }
        // JSON 숫자는 크기에 따라 Integer나 Long으로 읽히므로 Number로 받아 변환한다.
        return inquiries.param("title", title)
            .get<Number?>("inquiries.find { it.questionTitle == title }.inquiryId")
            ?.toLong()
    }

    private fun ValidatableResponse.validateForbidden() {
        statusCode(403)
        body("code", equalTo("api.auth.forbidden"))
    }
}

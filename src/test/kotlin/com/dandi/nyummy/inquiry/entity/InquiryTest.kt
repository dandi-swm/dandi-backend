package com.dandi.nyummy.inquiry.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.inquiry.enum.InquiryCategory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 답변 여부·읽음·수정 가능 여부처럼 answeredAt에서 계산되는 규칙을 다룬다. */
class InquiryTest {

    private val userId = 1L
    private val answeredAt = Instant.parse("2026-10-10T03:00:00Z")
    private val readAt = Instant.parse("2026-10-10T04:00:00Z")

    private fun inquiry(answeredAt: Instant? = null, answerReadAt: Instant? = null) = Inquiry(
        userId = userId,
        category = InquiryCategory.QUESTION,
        questionTitle = "제목",
        questionContent = "내용",
    ).apply {
        this.answeredAt = answeredAt
        this.answerReadAt = answerReadAt
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // hasUnreadAnswer

    @Test
    fun `답변이 없으면 안 읽은 답변이 없다`() {
        assertFalse(inquiry().hasUnreadAnswer)
    }

    @Test
    fun `답변이 있고 읽지 않았으면 안 읽은 답변이 있다`() {
        assertTrue(inquiry(answeredAt = answeredAt).hasUnreadAnswer)
    }

    @Test
    fun `답변을 읽었으면 안 읽은 답변이 없다`() {
        assertFalse(inquiry(answeredAt = answeredAt, answerReadAt = readAt).hasUnreadAnswer)
    }

    // updateAnswerReadAt

    @Test
    fun `답변이 없으면 읽음 시각을 기록하지 않는다`() {
        val inquiry = inquiry()

        inquiry.updateAnswerReadAt(readAt)

        assertNull(inquiry.answerReadAt)
    }

    @Test
    fun `답변을 처음 읽으면 읽음 시각을 기록한다`() {
        val inquiry = inquiry(answeredAt = answeredAt)

        inquiry.updateAnswerReadAt(readAt)

        assertEquals(readAt, inquiry.answerReadAt)
    }

    @Test
    fun `이미 읽은 답변은 처음 읽은 시각을 유지한다`() {
        val inquiry = inquiry(answeredAt = answeredAt, answerReadAt = readAt)

        inquiry.updateAnswerReadAt(readAt.plusSeconds(3_600))

        assertEquals(readAt, inquiry.answerReadAt)
    }

    // updateQuestion

    @Test
    fun `답변이 없으면 질문과 이미지를 수정한다`() {
        val inquiry = inquiry()

        inquiry.updateQuestion(InquiryCategory.BUG_REPORT, "새 제목", "새 내용", "inquiries/1/new.png")

        assertEquals(InquiryCategory.BUG_REPORT, inquiry.category)
        assertEquals("새 제목", inquiry.questionTitle)
        assertEquals("새 내용", inquiry.questionContent)
        assertEquals("inquiries/1/new.png", inquiry.imageKey)
    }

    @Test
    fun `답변이 달린 문의는 수정하지 않는다`() {
        val inquiry = inquiry(answeredAt = answeredAt)

        assertBusinessException(InquiryErrorCode.ALREADY_ANSWERED) {
            inquiry.updateQuestion(InquiryCategory.BUG_REPORT, "새 제목", "새 내용", null)
        }
        assertEquals("제목", inquiry.questionTitle)
    }

    // validateOwnership

    @Test
    fun `다른 사용자의 문의는 거부한다`() {
        assertBusinessException(AuthErrorCode.FORBIDDEN) { inquiry().validateOwnership(userId + 1) }
    }

    // createAnswer · updateAnswer

    @Test
    fun `답변을 처음 등록하면 제목·내용과 답변 시각이 정해진다`() {
        val inquiry = inquiry()

        inquiry.createAnswer("답변 제목", "답변 내용", answeredAt)

        assertTrue(inquiry.isAnswered)
        assertEquals("답변 제목", inquiry.answerTitle)
        assertEquals("답변 내용", inquiry.answerContent)
        assertEquals(answeredAt, inquiry.answeredAt)
        assertNull(inquiry.answerReadAt)
    }

    @Test
    fun `이미 답변이 있으면 다시 등록하지 않는다`() {
        val inquiry = inquiry(answeredAt = answeredAt)

        assertFailsWith<IllegalStateException> {
            inquiry.createAnswer("답변 제목", "답변 내용", readAt)
        }
        assertEquals(answeredAt, inquiry.answeredAt)
    }

    @Test
    fun `답변을 수정해도 답변 시각과 읽음 기록은 그대로다`() {
        val inquiry = inquiry(answeredAt = answeredAt, answerReadAt = readAt)

        inquiry.updateAnswer("고친 제목", "고친 내용")

        assertEquals("고친 제목", inquiry.answerTitle)
        assertEquals("고친 내용", inquiry.answerContent)
        assertEquals(answeredAt, inquiry.answeredAt)
        assertEquals(readAt, inquiry.answerReadAt)
        assertFalse(inquiry.hasUnreadAnswer)
    }

    @Test
    fun `답변이 없으면 수정하지 않는다`() {
        val inquiry = inquiry()

        assertFailsWith<IllegalStateException> {
            inquiry.updateAnswer("고친 제목", "고친 내용")
        }
        assertNull(inquiry.answerTitle)
    }
}

package com.dandi.nyummy.inquiry.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.inquiry.dto.UpdateInquiryRequest
import com.dandi.nyummy.inquiry.entity.Inquiry
import com.dandi.nyummy.inquiry.enum.InquiryCategory
import com.dandi.nyummy.inquiry.repository.InquiryRepository
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 질문 수정·답변 저장 구간에서 문의를 다시 읽어 검증·판단하는지 다룬다. 트랜잭션 경계 자체는 단위 테스트 범위 밖이다. */
class InquiryTransactionServiceTest {

    private val userId = 1L
    private val inquiryId = 10L
    private val imageKey = "inquiries/$userId/2026/10/10/uuid.png"
    private val request = UpdateInquiryRequest(
        category = InquiryCategory.SUGGESTION,
        questionTitle = "새 제목",
        questionContent = "새 내용",
        imageKey = imageKey,
    )

    private val inquiryRepository = mockk<InquiryRepository>()
    private val inquiryTransactionService = InquiryTransactionService(inquiryRepository)

    private fun inquiry(ownerId: Long = userId, answeredAt: Instant? = null) = Inquiry(
        userId = ownerId,
        category = InquiryCategory.QUESTION,
        questionTitle = "제목",
        questionContent = "내용",
    ).apply { this.answeredAt = answeredAt }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    @Test
    fun `다시 읽은 문의에 변경을 적용한다`() {
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry()

        val result = inquiryTransactionService.updateQuestion(userId, inquiryId, request, imageKey)

        assertEquals(InquiryCategory.SUGGESTION, result.category)
        assertEquals("새 제목", result.questionTitle)
        assertEquals("새 내용", result.questionContent)
        assertEquals(imageKey, result.imageKey)
    }

    @Test
    fun `이미지를 확정하는 사이 답변이 달렸으면 수정하지 않는다`() {
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns
            inquiry(answeredAt = Instant.parse("2026-10-10T03:00:00Z"))

        assertBusinessException(InquiryErrorCode.ALREADY_ANSWERED) {
            inquiryTransactionService.updateQuestion(userId, inquiryId, request, imageKey)
        }
    }

    @Test
    fun `이미지를 확정하는 사이 삭제됐으면 수정하지 않는다`() {
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns null

        assertBusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND) {
            inquiryTransactionService.updateQuestion(userId, inquiryId, request, imageKey)
        }
    }

    @Test
    fun `다른 사용자의 문의는 수정하지 않는다`() {
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry(ownerId = userId + 1)

        assertBusinessException(AuthErrorCode.FORBIDDEN) {
            inquiryTransactionService.getUpdatableInquiry(userId, inquiryId)
        }
    }

    // updateAnswer

    @Test
    fun `답변이 없으면 등록하고 처음 등록으로 알린다`() {
        val answeredAt = Instant.parse("2026-10-20T03:00:00Z")
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry()

        val result = inquiryTransactionService.updateAnswer(inquiryId, "답변 제목", "답변 내용", answeredAt)

        assertTrue(result.isFirstAnswer)
        assertEquals("답변 제목", result.inquiry.answerTitle)
        assertEquals(answeredAt, result.inquiry.answeredAt)
    }

    @Test
    fun `답변이 있으면 제목·내용만 고치고 처음 등록이 아니라고 알린다`() {
        val firstAnsweredAt = Instant.parse("2026-10-19T03:00:00Z")
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns
            inquiry(answeredAt = firstAnsweredAt).apply {
                answerTitle = "오타 제목"
                answerContent = "오타 내용"
            }

        val result = inquiryTransactionService.updateAnswer(
            inquiryId,
            "고친 제목",
            "고친 내용",
            Instant.parse("2026-10-20T03:00:00Z"),
        )

        assertFalse(result.isFirstAnswer)
        assertEquals("고친 제목", result.inquiry.answerTitle)
        assertEquals("고친 내용", result.inquiry.answerContent)
        assertEquals(firstAnsweredAt, result.inquiry.answeredAt)
    }

    @Test
    fun `없거나 삭제된 문의에는 답변하지 않는다`() {
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns null

        assertBusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND) {
            inquiryTransactionService.updateAnswer(inquiryId, "답변 제목", "답변 내용", Instant.parse("2026-10-20T03:00:00Z"))
        }
    }
}

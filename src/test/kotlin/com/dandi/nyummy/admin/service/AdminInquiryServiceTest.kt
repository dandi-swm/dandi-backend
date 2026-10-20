package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.dto.UpdateInquiryAnswerRequest
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushResult
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.inquiry.dto.InquiryAnswerResult
import com.dandi.nyummy.inquiry.entity.Inquiry
import com.dandi.nyummy.inquiry.enum.InquiryCategory
import com.dandi.nyummy.inquiry.repository.InquiryRepository
import com.dandi.nyummy.inquiry.service.InquiryTransactionService
import com.dandi.nyummy.notification.service.NotificationService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 어드민 문의함 조회와 답변 후 알림을 다룬다. 등록·수정 판단은 InquiryTransactionServiceTest, 답변 필드 규칙은 InquiryTest가 맡는다.
 */
class AdminInquiryServiceTest {

    private val now = Instant.parse("2026-10-20T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val adminId = 7L
    private val userId = 1L
    private val inquiryId = 10L
    private val request = UpdateInquiryAnswerRequest(answerTitle = "답변 제목", answerContent = "답변 내용")

    private val inquiryRepository = mockk<InquiryRepository>()
    private val inquiryTransactionService = mockk<InquiryTransactionService>()
    private val imageService = mockk<ImageService>()
    private val notificationService = mockk<NotificationService>()
    private val appLinkProperties = AppLinkProperties(
        baseUrl = "https://link.test.com",
        homeUrl = "/home",
        signupUrl = "/signup",
        mealDetailUrl = "/meals",
        onboardingUrl = "/onboarding",
        inquiryDetailUrl = "/inquiries",
    )

    private val adminInquiryService = AdminInquiryService(
        inquiryRepository = inquiryRepository,
        inquiryTransactionService = inquiryTransactionService,
        imageService = imageService,
        notificationService = notificationService,
        appLinkProperties = appLinkProperties,
        clock = clock,
    )

    private val sentMessage = slot<PushMessage>()

    init {
        every { notificationService.sendServicePush(userId, capture(sentMessage)) } returns PushResult.EMPTY
        every { notificationService.deleteInvalidDeviceTokens(any()) } just runs
        every { imageService.createImageUrl(any()) } answers { "https://image.example/${firstArg<String>()}" }
    }

    private fun inquiry(
        id: Long = inquiryId,
        imageKey: String? = null,
        answeredAt: Instant? = null,
        answerReadAt: Instant? = null,
    ) = Inquiry(
        userId = userId,
        category = InquiryCategory.QUESTION,
        questionTitle = "제목",
        questionContent = "내용",
        imageKey = imageKey,
    ).apply {
        this.answerTitle = answeredAt?.let { "답변 제목" }
        this.answerContent = answeredAt?.let { "답변 내용" }
        this.answeredAt = answeredAt
        this.answerReadAt = answerReadAt
        // id는 DB가 채우는 val이라 테스트에서만 직접 넣는다.
        ReflectionTestUtils.setField(this, "id", id)
    }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    private fun givenAnswerResult(isFirstAnswer: Boolean) {
        every {
            inquiryTransactionService.updateAnswer(inquiryId, request.answerTitle, request.answerContent, now)
        } returns InquiryAnswerResult(inquiry(answeredAt = now), isFirstAnswer)
    }

    // getInquiries

    @Test
    fun `다음 묶음이 있으면 마지막 문의 id를 다음 커서로 준다`() {
        every { inquiryRepository.findInquiriesBeforeCursor(null, false, Long.MAX_VALUE, PageRequest.of(0, 2)) } returns
            SliceImpl(listOf(inquiry(id = 30), inquiry(id = 20)), PageRequest.of(0, 2), true)

        val response = adminInquiryService.getInquiries(category = null, isAnswered = false, cursor = null, size = 2)

        assertEquals(listOf(30L, 20L), response.inquiries.map { it.inquiryId })
        assertEquals(20L, response.nextCursor)
        assertTrue(response.hasNext)
    }

    @Test
    fun `마지막 묶음이면 다음 커서가 없다`() {
        every {
            inquiryRepository.findInquiriesBeforeCursor(InquiryCategory.BUG_REPORT, null, 20L, PageRequest.of(0, 2))
        } returns SliceImpl(listOf(inquiry(id = 10)), PageRequest.of(0, 2), false)

        val response = adminInquiryService.getInquiries(
            InquiryCategory.BUG_REPORT,
            isAnswered = null,
            cursor = 20L,
            size = 2,
        )

        assertNull(response.nextCursor)
        assertFalse(response.hasNext)
    }

    // getInquiry

    @Test
    fun `상세 조회는 사용자의 답변 읽음 상태를 바꾸지 않는다`() {
        val inquiry = inquiry(imageKey = "inquiries/1/a.png", answeredAt = now, answerReadAt = null)
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry

        val response = adminInquiryService.getInquiry(inquiryId)

        assertNull(inquiry.answerReadAt)
        assertNull(response.answerReadAt)
        assertEquals("https://image.example/inquiries/1/a.png", response.imageUrl)
    }

    @Test
    fun `없거나 삭제된 문의는 조회하지 않는다`() {
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns null

        assertBusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND) {
            adminInquiryService.getInquiry(inquiryId)
        }
    }

    // updateAnswer

    @Test
    fun `처음 답변하면 문의 상세로 가는 답변 알림을 보낸다`() {
        givenAnswerResult(isFirstAnswer = true)

        val response = adminInquiryService.updateAnswer(adminId, inquiryId, request)

        assertEquals("답변 제목", response.answerTitle)
        assertEquals(PushType.INQUIRY_ANSWERED, sentMessage.captured.type)
        assertEquals("답변 제목", sentMessage.captured.body)
        assertEquals("https://link.test.com/inquiries/$inquiryId", sentMessage.captured.appLink)
        verify(exactly = 1) { notificationService.deleteInvalidDeviceTokens(emptyList()) }
    }

    @Test
    fun `답변을 수정하면 알림을 보내지 않는다`() {
        givenAnswerResult(isFirstAnswer = false)

        adminInquiryService.updateAnswer(adminId, inquiryId, request)

        verify(exactly = 0) { notificationService.sendServicePush(any(), any()) }
    }

    @Test
    fun `알림 발송이 실패해도 저장된 답변을 반환한다`() {
        givenAnswerResult(isFirstAnswer = true)
        every { notificationService.sendServicePush(userId, any()) } throws IllegalStateException("FCM 장애")

        val response = adminInquiryService.updateAnswer(adminId, inquiryId, request)

        assertEquals("답변 제목", response.answerTitle)
        assertTrue(response.isAnswered)
    }
}

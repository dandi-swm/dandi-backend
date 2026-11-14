package com.dandi.nyummy.inquiry.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.image.dto.UploadedImageResult
import com.dandi.nyummy.image.enum.ImagePurpose
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.infra.discord.DiscordClient
import com.dandi.nyummy.inquiry.dto.CreateInquiryRequest
import com.dandi.nyummy.inquiry.dto.UpdateInquiryRequest
import com.dandi.nyummy.inquiry.entity.Inquiry
import com.dandi.nyummy.inquiry.enum.InquiryCategory
import com.dandi.nyummy.inquiry.repository.InquiryRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.client.ResourceAccessException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 이미지 확정 순서(검증 → 확정 → 저장)와 새 문의 알림, cursor 계산, 읽음·삭제 처리를 다룬다.
 * 이미지 공통 검증은 ImageServiceTest, answeredAt 기반 규칙은 InquiryTest가 맡는다.
 */
class InquiryServiceTest {

    private val now = Instant.parse("2026-10-10T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = 1L
    private val inquiryId = 10L
    private val imageKey = "inquiries/$userId/2026/10/10/uuid.png"
    private val newImageKey = "inquiries/$userId/2026/10/10/new-uuid.png"

    private val inquiryRepository = mockk<InquiryRepository>()
    private val imageService = mockk<ImageService>()
    private val inquiryTransactionService = mockk<InquiryTransactionService>()
    private val discordClient = mockk<DiscordClient>()

    private val inquiryService = InquiryService(
        inquiryRepository = inquiryRepository,
        imageService = imageService,
        inquiryTransactionService = inquiryTransactionService,
        discordClient = discordClient,
        clock = clock,
    )

    private val savedInquiry = slot<Inquiry>()

    init {
        every { inquiryRepository.save(capture(savedInquiry)) } answers { firstArg() }
        every { inquiryRepository.existsByImageKey(any()) } returns false
        every { imageService.confirmUpload(userId, ImagePurpose.INQUIRY, any(), any()) } answers {
            UploadedImageResult(imageKey = thirdArg(), capturedAt = null)
        }
        every { imageService.createImageUrl(any()) } answers { "https://image.example/${firstArg<String>()}" }
        every { discordClient.sendMessage(any()) } just Runs
    }

    private fun inquiry(
        id: Long = inquiryId,
        ownerId: Long = userId,
        imageKey: String? = null,
        answeredAt: Instant? = null,
    ) = Inquiry(
        userId = ownerId,
        category = InquiryCategory.QUESTION,
        questionTitle = "제목",
        questionContent = "내용",
        imageKey = imageKey,
    ).apply {
        this.answeredAt = answeredAt
        // id는 DB가 채우는 val이라 테스트에서만 직접 넣는다.
        ReflectionTestUtils.setField(this, "id", id)
    }

    private fun createRequest(imageKey: String? = null) = CreateInquiryRequest(
        category = InquiryCategory.BUG_REPORT,
        questionTitle = "제목",
        questionContent = "내용",
        imageKey = imageKey,
    )

    private fun updateRequest(imageKey: String?) = UpdateInquiryRequest(
        category = InquiryCategory.SUGGESTION,
        questionTitle = "새 제목",
        questionContent = "새 내용",
        imageKey = imageKey,
    )

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // createInquiry

    @Test
    fun `이미지가 없으면 확정 없이 저장한다`() {
        inquiryService.createInquiry(userId, createRequest(), appVersion = null)

        assertNull(savedInquiry.captured.imageKey)
        assertEquals(userId, savedInquiry.captured.userId)
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
    }

    @Test
    fun `이미지가 있으면 중복 검사 후 문의 용도로 확정하고 저장한다`() {
        inquiryService.createInquiry(userId, createRequest(imageKey), appVersion = null)

        assertEquals(imageKey, savedInquiry.captured.imageKey)
        verifyOrder {
            inquiryRepository.existsByImageKey(imageKey)
            imageService.confirmUpload(userId, ImagePurpose.INQUIRY, imageKey, any())
            inquiryRepository.save(any())
        }
    }

    @Test
    fun `이미 사용된 imageKey면 확정하지 않고 거부한다`() {
        // 확정 뒤에 거부하면 객체가 이미 status=committed라 라이프사이클이 정리하지 못한다.
        every { inquiryRepository.existsByImageKey(imageKey) } returns true

        assertBusinessException(InquiryErrorCode.DUPLICATE_IMAGE_KEY) {
            inquiryService.createInquiry(userId, createRequest(imageKey), appVersion = null)
        }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
        verify(exactly = 0) { inquiryRepository.save(any()) }
        verify(exactly = 0) { discordClient.sendMessage(any()) }
    }

    @Test
    fun `이미지 확정에서 거부되면 저장하지 않는다`() {
        every { imageService.confirmUpload(userId, ImagePurpose.INQUIRY, imageKey, any()) } throws
            BusinessException(S3ErrorCode.INVALID_KEY)

        assertBusinessException(S3ErrorCode.INVALID_KEY) {
            inquiryService.createInquiry(userId, createRequest(imageKey), appVersion = null)
        }
        verify(exactly = 0) { inquiryRepository.save(any()) }
    }

    @Test
    fun `저장한 뒤 운영자 채널에 문의 id와 유형만 알린다`() {
        every { inquiryRepository.save(any()) } answers {
            firstArg<Inquiry>().also { ReflectionTestUtils.setField(it, "id", inquiryId) }
        }
        val message = slot<String>()
        every { discordClient.sendMessage(capture(message)) } just Runs

        inquiryService.createInquiry(userId, createRequest(), appVersion = null)

        verifyOrder {
            inquiryRepository.save(any())
            discordClient.sendMessage(any())
        }
        assertContains(message.captured, "inquiryId=$inquiryId")
        assertContains(message.captured, "category=${InquiryCategory.BUG_REPORT}")
        // 사용자가 쓴 글은 외부 서비스에 보내지 않는다.
        assertFalse(message.captured.contains("제목"))
        assertFalse(message.captured.contains("내용"))
    }

    @Test
    fun `Discord 알림이 실패해도 문의 생성은 성공한다`() {
        every { discordClient.sendMessage(any()) } throws ResourceAccessException("timeout")

        inquiryService.createInquiry(userId, createRequest(), appVersion = null)

        verify { inquiryRepository.save(any()) }
    }

    // getInquiries

    @Test
    fun `첫 요청은 가장 큰 id를 기준으로 size만큼 조회한다`() {
        every { inquiryRepository.findInquiriesByUserIdBeforeCursor(any(), any(), any()) } returns
            SliceImpl(emptyList(), PageRequest.of(0, 10), false)

        inquiryService.getInquiries(userId, cursor = null, size = 10)

        verify { inquiryRepository.findInquiriesByUserIdBeforeCursor(userId, Long.MAX_VALUE, PageRequest.of(0, 10)) }
    }

    @Test
    fun `다음 묶음이 있으면 마지막 문의 id를 nextCursor로 준다`() {
        val inquiries = listOf(inquiry(id = 9), inquiry(id = 7))
        every { inquiryRepository.findInquiriesByUserIdBeforeCursor(userId, 10L, PageRequest.of(0, 2)) } returns
            SliceImpl(inquiries, PageRequest.of(0, 2), true)

        val result = inquiryService.getInquiries(userId, cursor = 10L, size = 2)

        assertEquals(listOf(9L, 7L), result.inquiries.map { it.inquiryId })
        assertEquals(7L, result.nextCursor)
        assertTrue(result.hasNext)
    }

    @Test
    fun `마지막 묶음이면 nextCursor는 null이다`() {
        every { inquiryRepository.findInquiriesByUserIdBeforeCursor(userId, 10L, PageRequest.of(0, 2)) } returns
            SliceImpl(listOf(inquiry(id = 3)), PageRequest.of(0, 2), false)

        val result = inquiryService.getInquiries(userId, cursor = 10L, size = 2)

        assertNull(result.nextCursor)
        assertFalse(result.hasNext)
    }

    // getInquiry

    @Test
    fun `답변이 달린 문의를 조회하면 읽음 시각을 기록한다`() {
        val inquiry = inquiry(answeredAt = now.minusSeconds(600))
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry

        inquiryService.getInquiry(userId, inquiryId)

        assertEquals(now, inquiry.answerReadAt)
    }

    @Test
    fun `이미지가 있으면 조회 URL을 만들어 준다`() {
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry(imageKey = imageKey)

        val result = inquiryService.getInquiry(userId, inquiryId)

        assertEquals("https://image.example/$imageKey", result.imageUrl)
    }

    @Test
    fun `이미지가 없으면 조회 URL을 만들지 않는다`() {
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry()

        val result = inquiryService.getInquiry(userId, inquiryId)

        assertNull(result.imageUrl)
        verify(exactly = 0) { imageService.createImageUrl(any()) }
    }

    @Test
    fun `없거나 삭제된 문의는 조회하지 않는다`() {
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns null

        assertBusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND) { inquiryService.getInquiry(userId, inquiryId) }
    }

    @Test
    fun `다른 사용자의 문의는 읽음 처리하지 않고 거부한다`() {
        val inquiry = inquiry(ownerId = userId + 1, answeredAt = now.minusSeconds(600))
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry

        assertBusinessException(AuthErrorCode.FORBIDDEN) { inquiryService.getInquiry(userId, inquiryId) }
        assertNull(inquiry.answerReadAt)
    }

    // updateInquiry

    private fun givenUpdatable(current: Inquiry) {
        every { inquiryTransactionService.getUpdatableInquiry(userId, inquiryId) } returns current
        every { inquiryTransactionService.updateQuestion(userId, inquiryId, any(), any()) } answers {
            current.apply {
                updateQuestion(
                    thirdArg<UpdateInquiryRequest>().category,
                    thirdArg<UpdateInquiryRequest>().questionTitle,
                    thirdArg<UpdateInquiryRequest>().questionContent,
                    arg(3),
                )
            }
        }
    }

    @Test
    fun `imageKey가 null이면 확정 없이 이미지를 지운다`() {
        givenUpdatable(inquiry(imageKey = imageKey))

        val result = inquiryService.updateInquiry(userId, inquiryId, updateRequest(imageKey = null))

        assertNull(result.imageUrl)
        verify { inquiryTransactionService.updateQuestion(userId, inquiryId, any(), null) }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
    }

    @Test
    fun `기존과 같은 imageKey면 다시 확정하지 않고 유지한다`() {
        givenUpdatable(inquiry(imageKey = imageKey))

        inquiryService.updateInquiry(userId, inquiryId, updateRequest(imageKey = imageKey))

        verify { inquiryTransactionService.updateQuestion(userId, inquiryId, any(), imageKey) }
        verify(exactly = 0) { inquiryRepository.existsByImageKey(any()) }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
    }

    @Test
    fun `새 imageKey면 검증 후 확정하고 별도 트랜잭션에서 교체한다`() {
        givenUpdatable(inquiry(imageKey = imageKey))

        val result = inquiryService.updateInquiry(userId, inquiryId, updateRequest(imageKey = newImageKey))

        assertEquals("https://image.example/$newImageKey", result.imageUrl)
        verifyOrder {
            inquiryTransactionService.getUpdatableInquiry(userId, inquiryId)
            inquiryRepository.existsByImageKey(newImageKey)
            imageService.confirmUpload(userId, ImagePurpose.INQUIRY, newImageKey, any())
            inquiryTransactionService.updateQuestion(userId, inquiryId, any(), newImageKey)
        }
    }

    @Test
    fun `수정할 수 없는 문의면 새 이미지를 확정하지 않는다`() {
        // 수정이 거부될 이미지를 확정하면 status=committed인 고아 객체로 남는다.
        every { inquiryTransactionService.getUpdatableInquiry(userId, inquiryId) } throws
            BusinessException(InquiryErrorCode.ALREADY_ANSWERED)

        assertBusinessException(InquiryErrorCode.ALREADY_ANSWERED) {
            inquiryService.updateInquiry(userId, inquiryId, updateRequest(imageKey = newImageKey))
        }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
        verify(exactly = 0) { inquiryTransactionService.updateQuestion(any(), any(), any(), any()) }
    }

    @Test
    fun `새 imageKey가 이미 사용됐으면 확정과 수정을 하지 않는다`() {
        givenUpdatable(inquiry(imageKey = imageKey))
        every { inquiryRepository.existsByImageKey(newImageKey) } returns true

        assertBusinessException(InquiryErrorCode.DUPLICATE_IMAGE_KEY) {
            inquiryService.updateInquiry(userId, inquiryId, updateRequest(imageKey = newImageKey))
        }
        verify(exactly = 0) { imageService.confirmUpload(any(), any(), any(), any()) }
        verify(exactly = 0) { inquiryTransactionService.updateQuestion(any(), any(), any(), any()) }
    }

    // deleteInquiry

    @Test
    fun `답변이 달린 문의도 삭제 시각을 기록한다`() {
        val inquiry = inquiry(answeredAt = now.minusSeconds(600))
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry

        inquiryService.deleteInquiry(userId, inquiryId)

        assertEquals(now, inquiry.deletedAt)
    }

    @Test
    fun `다른 사용자의 문의는 삭제하지 않는다`() {
        val inquiry = inquiry(ownerId = userId + 1)
        every { inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId) } returns inquiry

        assertBusinessException(AuthErrorCode.FORBIDDEN) { inquiryService.deleteInquiry(userId, inquiryId) }
        assertNull(inquiry.deletedAt)
    }
}

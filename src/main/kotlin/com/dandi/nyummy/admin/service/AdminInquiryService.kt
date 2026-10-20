package com.dandi.nyummy.admin.service

import com.dandi.nyummy.admin.config.ConditionalOnAdminEnabled
import com.dandi.nyummy.admin.dto.AdminInquiriesResponse
import com.dandi.nyummy.admin.dto.AdminInquiryResponse
import com.dandi.nyummy.admin.dto.UpdateInquiryAnswerRequest
import com.dandi.nyummy.admin.mapper.toAdminInquiryResponse
import com.dandi.nyummy.admin.mapper.toAdminInquirySummaryResponse
import com.dandi.nyummy.config.AppLinkProperties
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.infra.push.dto.PushMessage
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.inquiry.entity.Inquiry
import com.dandi.nyummy.inquiry.enum.InquiryCategory
import com.dandi.nyummy.inquiry.repository.InquiryRepository
import com.dandi.nyummy.inquiry.service.InquiryTransactionService
import com.dandi.nyummy.notification.NotificationId
import com.dandi.nyummy.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
@ConditionalOnAdminEnabled
class AdminInquiryService(
    private val inquiryRepository: InquiryRepository,
    private val inquiryTransactionService: InquiryTransactionService,
    private val imageService: ImageService,
    private val notificationService: NotificationService,
    private val appLinkProperties: AppLinkProperties,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AdminInquiryService::class.java)
    }

    /**
     * 어드민 문의함을 최신순으로 조회한다. 유형·답변 여부가 null이면 그 조건으로 거르지 않는다.
     */
    @Transactional(readOnly = true)
    fun getInquiries(
        category: InquiryCategory?,
        isAnswered: Boolean?,
        cursor: Long?,
        size: Int,
    ): AdminInquiriesResponse {
        val inquiries = inquiryRepository.findInquiriesBeforeCursor(
            category = category,
            isAnswered = isAnswered,
            cursor = cursor ?: Long.MAX_VALUE,
            pageable = PageRequest.of(0, size),
        )

        return AdminInquiriesResponse(
            inquiries = inquiries.content.map { it.toAdminInquirySummaryResponse() },
            nextCursor = if (inquiries.hasNext()) inquiries.content.last().id else null,
            hasNext = inquiries.hasNext(),
        )
    }

    /**
     * 문의 하나를 조회한다. 유저용 조회와 달리 답변 읽음을 기록하지 않는다. 어드민이 열어 본 것을 사용자가
     * 읽은 것으로 만들지 않기 위해서다.
     *
     * @throws BusinessException [InquiryErrorCode.INQUIRY_NOT_FOUND] 문의가 없거나, 삭제된 경우
     */
    @Transactional(readOnly = true)
    fun getInquiry(inquiryId: Long): AdminInquiryResponse {
        val inquiry = inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId)
            ?: throw BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND)

        return convertAdminInquiryResponse(inquiry)
    }

    /**
     * 답변을 등록하거나 고친다. 처음 등록일 때만 사용자에게 알린다.
     *
     * 수정은 오타 정정용이라 알리지 않고 읽음 기록도 그대로 둔다. 저장은 짧은 트랜잭션으로 먼저 커밋하고,
     * 알림은 트랜잭션 밖에서 보낸다. FCM 호출 동안 DB 커넥션을 잡지 않고, 알림이 실패해도 답변은 남는다.
     *
     * @throws BusinessException [InquiryErrorCode.INQUIRY_NOT_FOUND] 문의가 없거나, 삭제된 경우
     */
    fun updateAnswer(adminId: Long, inquiryId: Long, request: UpdateInquiryAnswerRequest): AdminInquiryResponse {
        val (inquiry, isFirstAnswer) = inquiryTransactionService.updateAnswer(
            inquiryId = inquiryId,
            answerTitle = request.answerTitle,
            answerContent = request.answerContent,
            answeredAt = Instant.now(clock),
        )

        // 감사 로그가 생기기 전까지 누가 답변했는지 남기는 유일한 기록
        logger.info("문의 답변 {}: adminId={}, inquiryId={}", if (isFirstAnswer) "등록" else "수정", adminId, inquiryId)

        if (isFirstAnswer) {
            sendAnswerPush(inquiry)
        }

        return convertAdminInquiryResponse(inquiry)
    }

    /**
     * 답변 등록을 서비스 알림으로 보낸다. 수신 거부·토큰 없음은 [NotificationService.sendServicePush]가 조용히 넘긴다.
     */
    private fun sendAnswerPush(inquiry: Inquiry) {
        try {
            val result = notificationService.sendServicePush(
                inquiry.userId,
                PushMessage(
                    type = PushType.INQUIRY_ANSWERED,
                    notificationId = NotificationId.createInquiryAnswerId(inquiry.id),
                    title = "문의에 답변이 왔다냥 🐾",
                    body = inquiry.answerTitle.orEmpty(),
                    appLink = appLinkProperties.createInquiryDetailLink(inquiry.id),
                ),
            )

            notificationService.deleteInvalidDeviceTokens(result.invalidTokens)
        } catch (e: RuntimeException) {
            logger.error("문의 답변 알림 발송 실패: inquiryId={}", inquiry.id, e)
        }
    }

    private fun convertAdminInquiryResponse(inquiry: Inquiry): AdminInquiryResponse =
        inquiry.toAdminInquiryResponse(imageUrl = inquiry.imageKey?.let { imageService.createImageUrl(it) })
}

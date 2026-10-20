package com.dandi.nyummy.inquiry.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.inquiry.dto.InquiryAnswerResult
import com.dandi.nyummy.inquiry.dto.UpdateInquiryRequest
import com.dandi.nyummy.inquiry.entity.Inquiry
import com.dandi.nyummy.inquiry.repository.InquiryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 외부 호출과 분리해 짧게 커밋할 문의 트랜잭션 구간을 모은 빈.
 *
 * [InquiryService.updateInquiry]는 이미지 확정(S3 호출) 동안, 어드민 답변은 알림 발송(FCM 호출) 동안 트랜잭션을
 * 열지 않으므로 변경 저장만 이 빈의 @Transactional 메서드로 짧게 커밋한다. 같은 클래스 안에서 호출하면 프록시를
 * 거치지 않아 트랜잭션이 걸리지 않으므로 별도 빈으로 둔다.
 */
@Service
class InquiryTransactionService(private val inquiryRepository: InquiryRepository) {

    /**
     * 수정할 수 있는 문의를 조회한다. 삭제되지 않았고, 요청자의 문의이며, 답변이 없어야 한다.
     *
     * @throws BusinessException [InquiryErrorCode.INQUIRY_NOT_FOUND] 문의가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 문의인 경우
     * @throws BusinessException [InquiryErrorCode.ALREADY_ANSWERED] 답변이 등록된 문의인 경우
     */
    @Transactional(readOnly = true)
    fun getUpdatableInquiry(userId: Long, inquiryId: Long): Inquiry {
        val inquiry = inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId)
            ?: throw BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND)

        inquiry.validateOwnership(userId)
        inquiry.validateQuestionUpdate()

        return inquiry
    }

    /**
     * 문의를 다시 읽어 검증한 뒤 질문(유형·제목·내용·첨부 이미지) 변경을 적용하고 커밋한다.
     * 이미지 확정과 응답 변환까지 포함한 수정 흐름 전체는 [InquiryService.updateInquiry]가 맡는다.
     *
     * 호출 전에 읽은 엔티티를 받아 merge하지 않는다. 그사이 답변이 등록됐다면 merge가 answered_at을 덮어써
     * 답변을 지울 수 있기 때문이다. 다시 읽으면 그사이 답변·삭제도 다시 검증된다.
     *
     * @param imageKey 확정이 끝난 이미지 키. null이면 이미지를 지운다
     * @return 변경이 커밋된 [Inquiry]
     * @throws BusinessException [InquiryErrorCode.INQUIRY_NOT_FOUND] 문의가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 문의인 경우
     * @throws BusinessException [InquiryErrorCode.ALREADY_ANSWERED] 답변이 등록된 문의인 경우
     */
    @Transactional
    fun updateQuestion(userId: Long, inquiryId: Long, request: UpdateInquiryRequest, imageKey: String?): Inquiry {
        val inquiry = getUpdatableInquiry(userId, inquiryId)

        inquiry.updateQuestion(request.category, request.questionTitle, request.questionContent, imageKey)

        return inquiry
    }

    /**
     * 답변을 등록하거나 고치고 커밋한다. 답변이 없으면 등록하고 답변 시각을 [answeredAt]으로 정하며,
     * 이미 있으면 제목·내용만 고친다(오타 정정용 — 답변 시각과 읽음 기록은 그대로).
     *
     * 등록·수정 판단을 같은 트랜잭션에서 한다. 따로 읽고 판단하면 그사이 다른 어드민이 등록한 답변을
     * 처음 등록으로 보고 알림을 한 번 더 보낼 수 있다.
     *
     * @throws BusinessException [InquiryErrorCode.INQUIRY_NOT_FOUND] 문의가 없거나, 삭제된 경우
     */
    @Transactional
    fun updateAnswer(
        inquiryId: Long,
        answerTitle: String,
        answerContent: String,
        answeredAt: Instant,
    ): InquiryAnswerResult {
        val inquiry = inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId)
            ?: throw BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND)

        val isFirstAnswer = !inquiry.isAnswered
        if (isFirstAnswer) {
            inquiry.createAnswer(answerTitle, answerContent, answeredAt)
        } else {
            inquiry.updateAnswer(answerTitle, answerContent)
        }

        return InquiryAnswerResult(inquiry, isFirstAnswer)
    }
}

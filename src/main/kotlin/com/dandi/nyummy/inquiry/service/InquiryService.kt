package com.dandi.nyummy.inquiry.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.image.enum.ImagePurpose
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.infra.discord.DiscordClient
import com.dandi.nyummy.inquiry.dto.CreateInquiryRequest
import com.dandi.nyummy.inquiry.dto.InquiriesResponse
import com.dandi.nyummy.inquiry.dto.InquiryResponse
import com.dandi.nyummy.inquiry.dto.UpdateInquiryRequest
import com.dandi.nyummy.inquiry.entity.Inquiry
import com.dandi.nyummy.inquiry.mapper.toInquiry
import com.dandi.nyummy.inquiry.mapper.toInquiryResponse
import com.dandi.nyummy.inquiry.mapper.toInquirySummaryResponse
import com.dandi.nyummy.inquiry.repository.InquiryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClientException
import java.time.Clock
import java.time.Instant

@Service
class InquiryService(
    private val inquiryRepository: InquiryRepository,
    private val imageService: ImageService,
    private val inquiryTransactionService: InquiryTransactionService,
    private val discordClient: DiscordClient,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(InquiryService::class.java)
    }

    /**
     * 사용자의 문의를 생성한다. 이미지는 선택이며, 있으면 확정(status=committed)한 뒤 저장한다.
     *
     * 트랜잭션을 열지 않는다. 이미지 확정은 S3 조회·다운로드를 거치므로, 그동안 DB 커넥션을 잡아두지 않는다.
     * 저장은 save 자체의 트랜잭션으로 충분하다.
     *
     * 앱 버전은 공통 헤더(X-App-Version)에서 받아 문의를 쓴 시점의 값으로 남긴다. 헤더가 없으면 null이다.
     * 컬럼 길이를 넘으면 저장하지 않고 null로 둔다. 부가 정보 때문에 문의 생성이 실패하지 않게 하기 위함이다.
     *
     * 저장한 뒤 운영자 Discord 채널에 새 문의를 알린다. 트랜잭션 밖이라 알림을 기다리는 동안 DB 커넥션을 잡지 않는다.
     *
     * @throws BusinessException [InquiryErrorCode.DUPLICATE_IMAGE_KEY] 이미 문의에 사용된 imageKey인 경우
     * @throws BusinessException [S3ErrorCode.INVALID_KEY] imageKey가 요청자의 문의 경로(`inquiries/{userId}/`)가 아닌 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] imageKey에 해당하는 객체가 S3에 없는 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 실제 업로드된 크기가 0이거나 [ImagePurpose.INQUIRY]의 최대 크기를 초과하는 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] 실제 콘텐츠의 MIME 타입이 허용되지 않거나, imageKey의 확장자와 다른 경우
     */
    fun createInquiry(userId: Long, request: CreateInquiryRequest, appVersion: String?) {
        val imageKey = request.imageKey?.let { confirmImage(userId, it) }

        val inquiry = inquiryRepository.save(request.toInquiry(userId, imageKey, convertAppVersion(userId, appVersion)))

        sendDiscordMessage(inquiry)
    }

    /**
     * 운영자 채널에 새 문의를 알린다. 저장이 커밋된 뒤라 실패해도 문의 생성은 성공으로 둔다.
     *
     * 사용자가 쓴 제목·본문은 넣지 않는다. Discord는 외부 서비스라 개인정보가 섞이면 밖에 남는다. 내용은 어드민 화면에서 본다.
     */
    private fun sendDiscordMessage(inquiry: Inquiry) {
        try {
            discordClient.sendMessage("새 문의가 등록됐습니다. inquiryId=${inquiry.id} category=${inquiry.category}")
        } catch (e: RestClientException) {
            logger.warn("새 문의 Discord 알림을 보내지 못했습니다. inquiryId={}", inquiry.id, e)
        }
    }

    /**
     * 저장할 앱 버전을 반환한다. 헤더 값은 검증 없이 들어오므로, 컬럼 길이를 넘으면 warn 로그만 남기고 null로 바꾼다.
     */
    private fun convertAppVersion(userId: Long, appVersion: String?): String? {
        if (appVersion == null || appVersion.length <= Inquiry.APP_VERSION_MAX_LENGTH) {
            return appVersion
        }

        logger.warn(
            "앱 버전이 {}자를 넘어 저장하지 않습니다. userId={} appVersion={}",
            Inquiry.APP_VERSION_MAX_LENGTH,
            userId,
            appVersion,
        )
        return null
    }

    /**
     * 문의 이미지를 확정하고 확정된 키를 반환한다.
     *
     * 중복 검사를 확정보다 먼저 한다. 확정 뒤에 거부하면 객체가 이미 status=committed라
     * 라이프사이클 정리 대상에서 빠져 고아 객체로 남는다.
     * 소프트 삭제된 문의에 쓰인 키도 중복으로 본다.
     */
    private fun confirmImage(userId: Long, imageKey: String): String {
        if (inquiryRepository.existsByImageKey(imageKey)) {
            throw BusinessException(InquiryErrorCode.DUPLICATE_IMAGE_KEY)
        }

        return imageService.confirmUpload(userId, ImagePurpose.INQUIRY, imageKey).imageKey
    }

    /**
     * 사용자의 문의 목록을 최신순으로 [size]개씩 조회한다. 무한 스크롤용 cursor 방식이다.
     *
     * 첫 요청은 [cursor] 없이 보내고, 다음 요청부터는 직전 응답의 nextCursor(마지막 문의 id)를 보낸다.
     * offset 대신 id 기준점을 쓰므로 스크롤 중에 문의가 생기거나 지워져도 중복·누락이 없다.
     *
     * @param cursor 직전 묶음의 마지막 문의 id. null이면 가장 최신 문의부터 조회한다.
     */
    @Transactional(readOnly = true)
    fun getInquiries(userId: Long, cursor: Long?, size: Int): InquiriesResponse {
        val inquiries = inquiryRepository.findInquiriesByUserIdBeforeCursor(
            userId = userId,
            cursor = cursor ?: Long.MAX_VALUE,
            pageable = PageRequest.of(0, size),
        )

        return InquiriesResponse(
            inquiries = inquiries.content.map { it.toInquirySummaryResponse() },
            nextCursor = if (inquiries.hasNext()) inquiries.content.last().id else null,
            hasNext = inquiries.hasNext(),
        )
    }

    /**
     * 답변이 등록됐지만 아직 읽지 않은 문의가 있는지 확인한다. 삭제된 문의는 제외한다.
     *
     * 답변을 읽은 시각은 문의 상세 조회 때 기록된다.
     */
    @Transactional(readOnly = true)
    fun existsUnreadAnswer(userId: Long): Boolean = inquiryRepository.existsUnreadAnswerByUserId(userId)

    /**
     * 문의 하나의 상세 내용을 조회한다.
     *
     * 답변이 달린 문의를 처음 조회하면 답변 읽음 시각을 기록한다. home의 안 읽은 답변 여부가 이 값으로 계산되므로,
     * 조회 API지만 쓰기 트랜잭션으로 연다.
     *
     * @throws BusinessException [InquiryErrorCode.INQUIRY_NOT_FOUND] 문의가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 문의인 경우
     */
    @Transactional
    fun getInquiry(userId: Long, inquiryId: Long): InquiryResponse {
        val inquiry = inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId)
            ?: throw BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND)

        inquiry.validateOwnership(userId)
        inquiry.updateAnswerReadAt(Instant.now(clock))

        return convertInquiryResponse(inquiry)
    }

    /**
     * 문의의 유형, 제목, 내용, 이미지를 수정한다. 답변이 등록된 뒤에는 수정할 수 없다.
     *
     * 이미지는 PUT 의미를 따른다. imageKey가 null이면 이미지를 지우고, 기존과 같으면 그대로 두고,
     * 다르면 새 이미지를 확정해 교체한다. 지우거나 교체된 이전 객체는 S3에 그대로 둔다.
     *
     * 생성과 같은 이유로 이미지 확정(S3 호출) 동안 트랜잭션을 열지 않으며, 순서는 다음과 같다.
     * 1. 검증(소유권, 답변 여부)을 확정보다 먼저 한다. 수정이 거부될 이미지를 확정하면 고아 객체로 남는다.
     * 2. 새 이미지일 때만 확정한다.
     * 3. 변경은 [InquiryTransactionService]의 별도 트랜잭션에서 문의를 다시 읽어 적용한다. 확정하는 사이 답변이
     *    등록되거나 삭제됐을 수 있어 다시 검증하고, 처음 읽은 엔티티를 merge하면 그사이 등록된 답변을 덮어쓸 수 있기 때문이다.
     *
     * @throws BusinessException [InquiryErrorCode.INQUIRY_NOT_FOUND] 문의가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 문의인 경우
     * @throws BusinessException [InquiryErrorCode.ALREADY_ANSWERED] 답변이 등록된 문의인 경우
     * @throws BusinessException [InquiryErrorCode.DUPLICATE_IMAGE_KEY] 새 imageKey가 이미 문의에 사용된 경우
     * @throws BusinessException [S3ErrorCode.INVALID_KEY] 새 imageKey가 요청자의 문의 경로(`inquiries/{userId}/`)가 아닌 경우
     * @throws BusinessException [S3ErrorCode.OBJECT_NOT_FOUND] 새 imageKey에 해당하는 객체가 S3에 없는 경우
     * @throws BusinessException [S3ErrorCode.FILE_SIZE_EXCEEDED] 실제 업로드된 크기가 0이거나 [ImagePurpose.INQUIRY]의 최대 크기를 초과하는 경우
     * @throws BusinessException [S3ErrorCode.UNSUPPORTED_CONTENT_TYPE] 실제 콘텐츠의 MIME 타입이 허용되지 않거나, imageKey의 확장자와 다른 경우
     */
    fun updateInquiry(userId: Long, inquiryId: Long, request: UpdateInquiryRequest): InquiryResponse {
        val inquiry = inquiryTransactionService.getUpdatableInquiry(userId, inquiryId)

        val imageKey = when (val requestImageKey = request.imageKey) {
            null, inquiry.imageKey -> requestImageKey
            else -> confirmImage(userId, requestImageKey)
        }

        val updatedInquiry = inquiryTransactionService.updateQuestion(userId, inquiryId, request, imageKey)

        return convertInquiryResponse(updatedInquiry)
    }

    /**
     * 문의를 상세 응답으로 변환한다. 이미지가 있으면 조회용 presigned URL을 만들어 넣는다.
     */
    private fun convertInquiryResponse(inquiry: Inquiry): InquiryResponse =
        inquiry.toInquiryResponse(imageUrl = inquiry.imageKey?.let { imageService.createImageUrl(it) })

    /**
     * 문의를 소프트 삭제한다. 답변이 등록된 문의도 삭제할 수 있다.
     *
     * 첨부 이미지는 S3에서 지우지 않고 그대로 둔다.
     *
     * @throws BusinessException [InquiryErrorCode.INQUIRY_NOT_FOUND] 문의가 없거나, 이미 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 문의인 경우
     */
    @Transactional
    fun deleteInquiry(userId: Long, inquiryId: Long) {
        val inquiry = inquiryRepository.findInquiryByIdAndDeletedAtIsNull(inquiryId)
            ?: throw BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND)

        inquiry.validateOwnership(userId)
        inquiry.updateDeletedAt(Instant.now(clock))
    }

    /**
     * 소프트 삭제한 문의를 되돌린다. 삭제 직후 앱의 되돌리기에서 호출하며, 복구 기한은 두지 않는다.
     *
     * 삭제되지 않은 문의도 그대로 성공한다(멱등). 되돌리기를 여러 번 눌러도 결과가 같게 하기 위함이다.
     * deletedAt이 이미 null이면 같은 값을 넣어도 변경 감지에 걸리지 않아 UPDATE가 나가지 않으므로 따로 분기하지 않는다.
     *
     * 첨부 이미지는 삭제할 때 S3에 그대로 두었으므로 복구하면 다시 조회된다.
     *
     * @throws BusinessException [InquiryErrorCode.INQUIRY_NOT_FOUND] 문의가 없는 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 문의인 경우
     */
    @Transactional
    fun restoreInquiry(userId: Long, inquiryId: Long) {
        val inquiry = inquiryRepository.findByIdOrNull(inquiryId)
            ?: throw BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND)

        inquiry.validateOwnership(userId)
        inquiry.updateDeletedAt(null)
    }
}

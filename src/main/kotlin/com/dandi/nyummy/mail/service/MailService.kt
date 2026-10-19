package com.dandi.nyummy.mail.service

import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MailErrorCode
import com.dandi.nyummy.mail.dto.MailsResponse
import com.dandi.nyummy.mail.dto.RewardResponse
import com.dandi.nyummy.mail.entity.Mail
import com.dandi.nyummy.mail.enum.MailCategory
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.mapper.toMailSummaryResponse
import com.dandi.nyummy.mail.mapper.toRewardResponse
import com.dandi.nyummy.mail.repository.MailRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class MailService(
    private val mailRepository: MailRepository,
    private val coinService: CoinService,
    private val clock: Clock,
) {

    /**
     * 보상 상자 우편을 만든다. 제목은 등급 이름이다.
     *
     * 무엇을·얼마나·언제 줄지는 호출하는 쪽이 정하고, 여기서는 우편함에 넣기만 한다.
     * 트랜잭션은 호출하는 쪽이 연다. 저장이 save 한 번뿐이라 혼자 불려도 save가 스스로 커밋한다.
     *
     * @param userId 우편을 받을 사용자 ID
     * @param rewardBoxCategory 상자 등급
     * @param coinQuantity 상자에 든 코인 수
     * @param content 보상을 얻은 경로 문구
     * @param expiresAt 우편 만료 시각
     * @return 저장된 [Mail]
     */
    fun createRewardBoxMail(
        userId: Long,
        rewardBoxCategory: RewardBoxCategory,
        coinQuantity: Int,
        content: String,
        expiresAt: Instant,
    ): Mail = mailRepository.save(
        Mail(
            userId = userId,
            category = MailCategory.REWARD_BOX,
            title = rewardBoxCategory.description,
            content = content,
            coinQuantity = coinQuantity,
            rewardBoxCategory = rewardBoxCategory,
            expiresAt = expiresAt,
        ),
    )

    /**
     * 사용자의 우편 목록을 최신순으로 [size]개씩 조회한다. 무한 스크롤용 cursor 방식이다.
     *
     * 첫 요청은 [cursor] 없이 보내고, 다음 요청부터는 직전 응답의 nextCursor(마지막 우편 id)를 보낸다.
     * 만료된 우편은 받은 우편이어도 보여주지 않는다. 만료는 조회하는 시각을 기준으로 판단한다.
     *
     * @param cursor 직전 묶음의 마지막 우편 id. null이면 가장 최신 우편부터 조회한다.
     */
    @Transactional(readOnly = true)
    fun getMails(userId: Long, cursor: Long?, size: Int): MailsResponse {
        val mails = mailRepository.findMailsByUserIdBeforeCursor(
            userId = userId,
            cursor = cursor ?: Long.MAX_VALUE,
            now = Instant.now(clock),
            pageable = PageRequest.of(0, size),
        )

        return MailsResponse(
            mails = mails.content.map { it.toMailSummaryResponse() },
            nextCursor = if (mails.hasNext()) mails.content.last().id else null,
            hasNext = mails.hasNext(),
        )
    }

    /**
     * 우편 하나를 받고 지급한 보상을 반환한다. 우편에 든 코인을 잔액에 더하고 내역을 남긴다.
     *
     * 같은 우편을 동시에 받아 두 번 지급되지 않도록 우편 행을 잠근 뒤 검증한다. 뒤에 온 요청은 앞 요청이
     * 커밋할 때까지 기다렸다가 이미 받은 우편으로 거절된다. 잠금 순서는 우편 → 코인이다.
     *
     * 받은 우편만 삭제할 수 있으므로 삭제된 우편은 없는 우편으로 본다.
     * 아이템 지급은 아직 구현 전이라 우편에 든 아이템(mail_items)은 지급하지 않는다.
     *
     * @throws BusinessException [MailErrorCode.MAIL_NOT_FOUND] 우편이 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 우편인 경우
     * @throws BusinessException [MailErrorCode.ALREADY_CLAIMED] 이미 받은 우편인 경우
     * @throws BusinessException [MailErrorCode.MAIL_EXPIRED] 만료된 우편인 경우
     */
    @Transactional
    fun claimMail(userId: Long, mailId: Long): RewardResponse {
        val mail = mailRepository.findMailWithLockByIdAndDeletedAtIsNull(mailId)
            ?: throw BusinessException(MailErrorCode.MAIL_NOT_FOUND)

        mail.validateOwnership(userId)
        mail.claim(Instant.now(clock))

        mail.coinQuantity?.let { coinService.increaseBalance(userId, it, CoinHistoryCategory.MAIL_CLAIM, mail.id) }

        return mail.toRewardResponse()
    }
}

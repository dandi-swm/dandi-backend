package com.dandi.nyummy.mail.service

import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MailErrorCode
import com.dandi.nyummy.mail.dto.MailItemResult
import com.dandi.nyummy.mail.dto.MailsResponse
import com.dandi.nyummy.mail.dto.RewardResponse
import com.dandi.nyummy.mail.dto.TotalRewardResponse
import com.dandi.nyummy.mail.entity.Mail
import com.dandi.nyummy.mail.enum.MailCategory
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.mapper.toMailSummaryResponse
import com.dandi.nyummy.mail.mapper.toRewardItemResponses
import com.dandi.nyummy.mail.mapper.toRewardResponse
import com.dandi.nyummy.mail.mapper.toTotalRewardResponse
import com.dandi.nyummy.mail.repository.MailItemRepository
import com.dandi.nyummy.mail.repository.MailRepository
import com.dandi.nyummy.room.service.RoomService
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class MailService(
    private val mailRepository: MailRepository,
    private val mailItemRepository: MailItemRepository,
    private val coinService: CoinService,
    private val roomService: RoomService,
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
     * 받을 수 있는 우편이 있는지 확인한다. 홈 우편함 배지에 쓴다.
     *
     * 받지 않았고 만료되지 않은 우편만 본다. 만료는 조회하는 시각을 기준으로 판단하며, 목록 조회·받기와 같은 기준이다.
     * 받은 우편만 삭제할 수 있으므로 삭제된 우편은 해당하지 않는다.
     */
    @Transactional(readOnly = true)
    fun existsNotClaimedMail(userId: Long): Boolean =
        mailRepository.existsByUserIdAndClaimedAtIsNull(userId, Instant.now(clock))

    /**
     * 우편 하나를 받고 지급한 보상을 반환한다. 우편에 든 아이템은 개수만큼 보유 아이템으로 만들고,
     * 코인은 잔액에 더하고 내역을 남긴다. 보상 상자에 든 아이템도 같이 지급한다.
     *
     * 같은 우편을 동시에 받아 두 번 지급되지 않도록 우편 행을 잠근 뒤 검증한다. 뒤에 온 요청은 앞 요청이
     * 커밋할 때까지 기다렸다가 이미 받은 우편으로 거절된다. 잠금 순서는 우편 → 코인이다.
     *
     * 받은 우편만 삭제할 수 있으므로 삭제된 우편은 없는 우편으로 본다.
     * 응답의 아이템은 같은 아이템을 하나로 합치고, 우편에 넣은 순서로 준다.
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

        val mailItems = mailItemRepository.findMailItemsByMailIds(listOf(mail.id))
        createOwnedRoomItems(userId, mailItems)
        mail.coinQuantity?.let { coinService.increaseBalance(userId, it, CoinHistoryCategory.MAIL_CLAIM, mail.id) }

        return mail.toRewardResponse(items = mailItems.toRewardItemResponses())
    }

    /**
     * 안 받은 우편을 모두 받고 지급한 보상의 합계를 반환한다. 받는 일은 우편마다 [claimMail]과 같다.
     *
     * 이미 받은 우편과 만료된 우편은 조회에서 빼고, 남은 우편은 하나 받기와 같은 상태 전이([Mail.claim])를 거친다.
     * 같은 시각으로 거르고 받으므로 여기서 거부되는 우편은 없다. 개수 상한은 두지 않는다.
     *
     * 우편을 id 오름차순으로 모두 잠근 뒤 코인 행을 잠근다. 잠금 순서는 하나 받기와 같은 우편 → 코인이다.
     * 코인은 우편마다 지급해 내역이 우편마다 남는다. 코인 행은 첫 지급에서 잠기고 이후는 같은 잠금을 쓴다.
     * 응답의 아이템은 여러 우편의 같은 아이템을 하나로 합치고, 우편 순서 → 우편에 넣은 순서로 준다.
     *
     * 받을 우편이 없으면 coin과 items가 모두 null이다.
     */
    @Transactional
    fun claimMails(userId: Long): TotalRewardResponse {
        val now = Instant.now(clock)
        val mails = mailRepository.findMailsWithLockByUserIdAndClaimedAtIsNull(userId, now)
        if (mails.isEmpty()) {
            return TotalRewardResponse(coin = null, items = null)
        }

        mails.forEach { it.claim(now) }

        val mailItems = mailItemRepository.findMailItemsByMailIds(mails.map { it.id })
        createOwnedRoomItems(userId, mailItems)
        mails.forEach { mail ->
            mail.coinQuantity?.let { coinService.increaseBalance(userId, it, CoinHistoryCategory.MAIL_CLAIM, mail.id) }
        }

        return mails.toTotalRewardResponse(items = mailItems.toRewardItemResponses())
    }

    /**
     * 받은 우편을 소프트 삭제한다. 삭제 직후 앱의 되돌리기로 복구할 수 있도록 행은 남긴다.
     *
     * 이미 삭제된 우편도 그대로 성공한다(멱등). 삭제를 두 번 눌러도 결과가 같게 하기 위함이다.
     * 이때 삭제 시각은 다시 삭제한 시각으로 바뀐다.
     *
     * 받기와 겹쳐도 잠그지 않는다. 삭제는 받은 우편만, 받기는 안 받은 우편만 바꾸므로 한쪽은 거절된다.
     *
     * @throws BusinessException [MailErrorCode.MAIL_NOT_FOUND] 우편이 없는 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 우편인 경우
     * @throws BusinessException [MailErrorCode.MAIL_NOT_CLAIMED] 아직 받지 않은 우편인 경우
     */
    @Transactional
    fun deleteMail(userId: Long, mailId: Long) {
        val mail = mailRepository.findByIdOrNull(mailId)
            ?: throw BusinessException(MailErrorCode.MAIL_NOT_FOUND)

        mail.validateOwnership(userId)
        mail.updateDeletedAt(Instant.now(clock))
    }

    /**
     * 소프트 삭제한 우편을 되돌린다. 삭제 직후 앱의 되돌리기에서 호출하며, 복구 기한은 두지 않는다.
     *
     * 삭제되지 않은 우편도 그대로 성공한다(멱등). 되돌리기를 여러 번 눌러도 결과가 같게 하기 위함이다.
     * deletedAt이 이미 null이면 같은 값을 넣어도 변경 감지에 걸리지 않아 UPDATE가 나가지 않으므로 따로 분기하지 않는다.
     *
     * 만료 여부는 보지 않는다. 만료된 우편을 복구해도 목록에서는 계속 빠진다.
     *
     * @throws BusinessException [MailErrorCode.MAIL_NOT_FOUND] 우편이 없는 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 다른 사용자의 우편인 경우
     */
    @Transactional
    fun restoreMail(userId: Long, mailId: Long) {
        val mail = mailRepository.findByIdOrNull(mailId)
            ?: throw BusinessException(MailErrorCode.MAIL_NOT_FOUND)

        mail.validateOwnership(userId)
        mail.updateDeletedAt(null)
    }

    // 우편에 든 아이템을 개수만큼 펼쳐 보유 아이템으로 만든다. 아이템이 없으면 아무것도 하지 않는다.
    private fun createOwnedRoomItems(userId: Long, mailItems: List<MailItemResult>) {
        if (mailItems.isEmpty()) {
            return
        }
        roomService.createOwnedRoomItems(userId, mailItems.flatMap { item -> List(item.quantity) { item.itemId } })
    }
}

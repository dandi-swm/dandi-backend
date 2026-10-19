package com.dandi.nyummy.mail.controller

import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MailErrorCode
import com.dandi.nyummy.mail.dto.MailsResponse
import com.dandi.nyummy.mail.dto.RewardCoinResponse
import com.dandi.nyummy.mail.dto.RewardItemResponse
import com.dandi.nyummy.mail.dto.RewardResponse
import com.dandi.nyummy.mail.dto.TotalRewardResponse
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Mail", description = "우편 조회 · 받기 · 삭제 · 복구 API")
@RestController
@RequestMapping("/api/v1/mails")
class MailController(private val mailService: MailService) {

    @Operation(
        summary = "우편 목록 조회",
        description = "우편 목록을 최신순으로 조회한다. 무한 스크롤용 cursor 방식이다. " +
            "첫 요청은 cursor 없이 보내고, 다음 요청부터는 직전 응답의 nextCursor를 cursor로 보낸다. " +
            "hasNext가 false면 마지막 묶음이며 nextCursor는 null이다. " +
            "만료된 우편은 받은 우편이어도 포함하지 않으며, expiresAt이 null이면 만료되지 않는 우편이다. " +
            "이미지 URL은 내려주지 않으며, 앱이 https://cdn.nyummy.co.kr/ 뒤에 경로를 붙여 조립한다. " +
            "category로 경로를 고르고 imageId를 붙인다. ITEM은 아이템 id로 items/{imageId}.png, " +
            "REWARD_BOX는 상자 번호(1 브론즈, 2 실버, 3 골드)로 reward-boxes/{imageId}.png, " +
            "COIN은 코인 이미지 번호(지금은 1)로 coins/{imageId}.png다.",
    )
    @GetMapping
    fun getMails(
        @CurrentUser user: AuthUser,
        @Parameter(description = "직전 응답의 nextCursor. 첫 요청에서는 생략한다.") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "한 번에 조회할 개수 (1~50, 기본 10)")
        @RequestParam(defaultValue = "10")
        @Min(1)
        @Max(50)
        size: Int,
    ): MailsResponse = mailService.getMails(user.userId, cursor, size)

    @Operation(
        summary = "우편 하나 받기",
        description = "우편 하나를 받고 지급된 보상을 반환한다. 보상 상자·코인·아이템 모두 이 API로 받는다. " +
            "응답 구조는 우편 종류와 관계없이 같으며, 해당하는 값이 없으면 coin 또는 items가 null이다. " +
            "보상 상자는 들어 있던 코인과 아이템이 그대로 담기고, rewardBox에 상자 번호와 열기 애니메이션 URL(animationUrl)이 담긴다. " +
            "animationUrl은 아직 null이다. 코인·아이템 우편이면 rewardBox는 null이다. " +
            "이미지는 목록과 같이 imageId로 조립한다. coin은 coins/{imageId}.png, items는 items/{imageId}.png, " +
            "rewardBox는 reward-boxes/{imageId}.png다. 아이템 지급은 아직 구현 전이라 items는 항상 null이다.",
    )
    @ApiErrorCodes(
        mail = [MailErrorCode.MAIL_NOT_FOUND, MailErrorCode.ALREADY_CLAIMED, MailErrorCode.MAIL_EXPIRED],
        auth = [AuthErrorCode.FORBIDDEN],
    )
    @PostMapping("/{mailId}/claim")
    fun claimMail(
        @CurrentUser user: AuthUser,
        @Parameter(description = "우편 ID") @PathVariable("mailId") mailId: Long,
    ): RewardResponse = mailService.claimMail(user.userId, mailId)

    @Operation(
        summary = "우편 모두 받기",
        description = "안 받은 우편을 한 번에 받고, 지급된 보상의 합계를 반환한다. " +
            "보상 상자도 포함하며, 열기 연출 없이 들어 있던 코인과 아이템을 합계에 더한다. " +
            "받을 우편이 없으면 coin과 items가 null이다. 이미지는 하나 받기와 같이 imageId로 조립한다. " +
            "현재는 요청과 관계없이 고정 응답을 반환한다.",
    )
    @PostMapping("/claim-all")
    fun claimMails(@CurrentUser user: AuthUser): TotalRewardResponse = TotalRewardResponse(
        coin = RewardCoinResponse(
            quantity = 150,
            imageId = 1,
        ),
        items = listOf(
            RewardItemResponse(
                itemId = 2,
                name = "호박 쿠션",
                imageId = 2,
                quantity = 3,
            ),
        ),
    )

    @Operation(
        summary = "받은 우편 삭제",
        description = "받은 우편을 삭제한다. 아직 받지 않은 우편은 삭제할 수 없다. " +
            "실제로 지우지 않고 삭제 표시만 하므로 우편 복구 API로 되돌릴 수 있다. " +
            "현재는 요청과 관계없이 항상 204를 반환한다.",
    )
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @DeleteMapping("/{mailId}")
    fun deleteMail(
        @CurrentUser user: AuthUser,
        @Parameter(description = "우편 ID") @PathVariable("mailId") mailId: Long,
    ): ResponseEntity<Void> = ResponseEntity.noContent().build()

    @Operation(
        summary = "우편 복구",
        description = "삭제한 우편을 되돌린다. 복구 기한은 없다. 삭제되지 않은 우편에 요청해도 그대로 두고 성공한다. " +
            "현재는 요청과 관계없이 항상 204를 반환한다.",
    )
    @ApiResponse(responseCode = "204", description = "복구 성공")
    @PostMapping("/{mailId}/restore")
    fun restoreMail(
        @CurrentUser user: AuthUser,
        @Parameter(description = "우편 ID") @PathVariable("mailId") mailId: Long,
    ): ResponseEntity<Void> = ResponseEntity.noContent().build()
}

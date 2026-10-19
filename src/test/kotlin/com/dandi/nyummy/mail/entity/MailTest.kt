package com.dandi.nyummy.mail.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.ErrorCode
import com.dandi.nyummy.exception.errorcode.MailErrorCode
import com.dandi.nyummy.mail.enum.MailCategory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MailTest {

    private val userId = 1L
    private val now = Instant.parse("2026-10-19T03:00:00Z")

    private fun mail(expiresAt: Instant? = now.plusSeconds(60), claimedAt: Instant? = null) = Mail(
        userId = userId,
        category = MailCategory.COIN,
        title = "코인 100개",
        content = "식사 기록 보상",
        coinQuantity = 100,
        expiresAt = expiresAt,
    ).apply { this.claimedAt = claimedAt }

    private fun assertBusinessException(expected: ErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // claim

    @Test
    fun `받으면 받은 시각을 기록한다`() {
        val mail = mail()

        mail.claim(now)

        assertEquals(now, mail.claimedAt)
    }

    @Test
    fun `만료 시각이 없는 우편은 언제든 받을 수 있다`() {
        val mail = mail(expiresAt = null)

        mail.claim(now)

        assertEquals(now, mail.claimedAt)
    }

    @Test
    fun `이미 받은 우편은 다시 받을 수 없고 받은 시각도 그대로다`() {
        val claimedAt = now.minusSeconds(600)
        val mail = mail(claimedAt = claimedAt)

        assertBusinessException(MailErrorCode.ALREADY_CLAIMED) { mail.claim(now) }
        assertEquals(claimedAt, mail.claimedAt)
    }

    @Test
    fun `만료 시각이 지난 우편은 받을 수 없다`() {
        val mail = mail(expiresAt = now.minusSeconds(1))

        assertBusinessException(MailErrorCode.MAIL_EXPIRED) { mail.claim(now) }
        assertNull(mail.claimedAt)
    }

    @Test
    fun `만료 시각과 같은 순간부터 만료로 본다`() {
        val mail = mail(expiresAt = now)

        assertBusinessException(MailErrorCode.MAIL_EXPIRED) { mail.claim(now) }
    }

    // validateOwnership

    @Test
    fun `다른 사용자의 우편이면 거부한다`() {
        assertBusinessException(AuthErrorCode.FORBIDDEN) { mail().validateOwnership(requestUserId = 2L) }
    }
}

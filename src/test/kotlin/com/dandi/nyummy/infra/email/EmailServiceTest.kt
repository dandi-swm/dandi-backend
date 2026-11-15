package com.dandi.nyummy.infra.email

import com.dandi.nyummy.language.createTestMessageSource
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals

class EmailServiceTest {

    private val emailClient = mockk<EmailClient>()
    private val messageSource = createTestMessageSource()
    private val emailService = EmailService(emailClient, messageSource)

    private val sent = slot<EmailMessage>()

    init {
        every { emailClient.sendEmail(capture(sent)) } just runs
    }

    @Test
    fun `원본 언어로 보내면 지금까지와 같은 인증 코드 메일을 보낸다`() {
        emailService.sendAuthCode("cat@test.com", "123456", Language.KO)

        assertEquals(EmailMessage("cat@test.com", "[Nyummy] 인증 코드", "인증 코드는 123456 입니다."), sent.captured)
    }

    @Test
    fun `인증 코드 메일은 요청 언어로 보내고 앞자리 0도 그대로 넣는다`() {
        emailService.sendAuthCode("cat@test.com", "012345", Language.JA)

        assertEquals(messageSource.getMessage("email.authCode.subject", Language.JA), sent.captured.subject)
        assertEquals(messageSource.getMessage("email.authCode.text", Language.JA, "012345"), sent.captured.text)
        assertEquals("認証コードは 012345 です。", sent.captured.text)
    }

    @Test
    fun `원본 언어로 보내면 지금까지와 같은 임시 비밀번호 메일을 보낸다`() {
        emailService.sendTempPassword("cat@test.com", "Temp12345678", Language.KO)

        assertEquals(
            EmailMessage(
                "cat@test.com",
                "[Nyummy] 임시 비밀번호",
                "임시 비밀번호는 Temp12345678 입니다. 로그인 후 비밀번호를 변경해 주세요.",
            ),
            sent.captured,
        )
    }

    @Test
    fun `임시 비밀번호 메일은 요청 언어로 보낸다`() {
        emailService.sendTempPassword("cat@test.com", "Temp12345678", Language.EN)

        assertEquals(messageSource.getMessage("email.tempPassword.subject", Language.EN), sent.captured.subject)
        assertEquals(
            messageSource.getMessage("email.tempPassword.text", Language.EN, "Temp12345678"),
            sent.captured.text,
        )
    }
}

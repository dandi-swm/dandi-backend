package com.dandi.nyummy.infra.email

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.EmailErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.language.getMessage
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service

/**
 * 인증 메일을 만들어 보낸다. 제목과 본문은 메일을 보내는 요청의 언어로 문구 파일에서 꺼낸다.
 */
@Service
class EmailService(private val emailClient: EmailClient, private val messageSource: MessageSource) {

    /**
     * 인증 코드를 본문에 담아 이메일을 발송한다.
     *
     * @param email 수신자 이메일 주소
     * @param code 발송할 6자리 인증 코드. 문자열로 넘겨 MessageFormat이 숫자 서식(천 단위 쉼표)을 붙이지 않게 한다
     * @param language 인증 코드를 요청한 앱 언어
     * @throws BusinessException [EmailErrorCode.EMAIL_SEND_FAILED] 이메일 발송이 실패한 경우
     */
    fun sendAuthCode(email: String, code: String, language: Language) {
        emailClient.sendEmail(
            EmailMessage(
                to = email,
                subject = messageSource.getMessage("email.authCode.subject", language),
                text = messageSource.getMessage("email.authCode.text", language, code),
            ),
        )
    }

    /**
     * 임시 비밀번호를 본문에 담아 이메일을 발송한다.
     *
     * @param email 수신자 이메일 주소
     * @param tempPassword 발송할 평문 임시 비밀번호
     * @param language 비밀번호 재설정을 요청한 앱 언어
     * @throws BusinessException [EmailErrorCode.EMAIL_SEND_FAILED] 이메일 발송이 실패한 경우
     */
    fun sendTempPassword(email: String, tempPassword: String, language: Language) {
        emailClient.sendEmail(
            EmailMessage(
                to = email,
                subject = messageSource.getMessage("email.tempPassword.subject", language),
                text = messageSource.getMessage("email.tempPassword.text", language, tempPassword),
            ),
        )
    }
}

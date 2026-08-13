package com.dandi.nyummy.auth.service

import com.dandi.nyummy.auth.entity.Mail
import com.dandi.nyummy.auth.repository.MailRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

@Service
class MailService(
    private val mailRepository: MailRepository,
    private val mailSender: JavaMailSender,
    @Value("\${spring.mail.username}") private val fromAddress: String,
    @Value("\${app.mail.valid-time}") private val validTime: Duration,
) {
    companion object {
        private val random = SecureRandom()
    }

    /**
     * 인증 코드를 생성해 이메일로 발송한다. 기존 발송 이력이 있으면 코드를 새로 갱신해 재발송한다.
     *
     * @param email 인증 코드를 받을 이메일 주소
     * @throws BusinessException [AuthErrorCode.MAIL_RESEND_TOO_EARLY] 마지막 발송 후 [EMAIL_VALID_TIME]이 지나지 않은 경우
     */
    @Transactional
    fun sendEmail(email: String) {
        val existingMail = mailRepository.findByEmail(email)

        val code = createCode()

        if (existingMail != null) {
            if (Duration.between(existingMail.createdAt, Instant.now()) < validTime) {
                throw BusinessException(AuthErrorCode.MAIL_RESEND_TOO_EARLY)
            }

            existingMail.createdAt = Instant.now()
            existingMail.isVerified = false
            existingMail.code = code
        } else {
            mailRepository.save(
                Mail(
                    email = email,
                    code = code,
                ),
            )
        }

        send(email, code)
    }

    private fun send(email: String, code: String) {
        val message = SimpleMailMessage()
        message.from = fromAddress
        message.setTo(email)
        message.setSubject("[Nyummy] 이메일 인증 코드")
        message.setText("인증 코드: $code\n5분 안에 입력해주세요.")
        mailSender.send(message)
    }

    private fun createCode(): String = "%06d".format(random.nextInt(1_000_000))

    /**
     * 이메일로 발송된 인증 코드를 검증하고 해당 이메일을 인증 완료로 표시한다.
     *
     * @param email 인증할 이메일 주소
     * @param code 사용자가 입력한 인증 코드
     * @throws BusinessException [AuthErrorCode.MAIL_NOT_FOUND] 해당 이메일로 발송된 인증 코드가 없는 경우
     * @throws BusinessException [AuthErrorCode.MAIL_CODE_EXPIRED] 발송 후 [EMAIL_VALID_TIME]이 지난 경우
     * @throws BusinessException [AuthErrorCode.MAIL_CODE_MISMATCH] 인증 코드가 일치하지 않는 경우
     */
    @Transactional
    fun confirm(email: String, code: String) {
        val existingMail = mailRepository.findByEmail(email)
            ?: throw BusinessException(AuthErrorCode.MAIL_NOT_FOUND)

        if (Duration.between(existingMail.createdAt, Instant.now()) > validTime) {
            throw BusinessException(AuthErrorCode.MAIL_CODE_EXPIRED)
        }

        if (!existingMail.code.equals(code)) {
            throw BusinessException(AuthErrorCode.MAIL_CODE_MISMATCH)
        }

        existingMail.isVerified = true
    }
}

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
) {
    companion object {
        private val random = SecureRandom()
        private val EMAIL_VALID_TIME: Duration = Duration.ofMinutes(5)
    }

    @Transactional
    fun sendEmail(email: String) {
        val existingMail = mailRepository.findByEmail(email)

        val code = createCode()

        if (existingMail != null) {
            if (Duration.between(existingMail.createdAt, Instant.now()) < EMAIL_VALID_TIME) {
                throw BusinessException(AuthErrorCode.MAIL_RESEND_TOO_EARLY)
            }

            existingMail.createdAt = Instant.now()
            existingMail.isVerified = false
            existingMail.code = code
        } else {
            mailRepository.save(Mail(email, code))
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

    @Transactional
    fun confirm(email: String, code: String) {
        val existingMail = mailRepository.findByEmail(email)
            ?: throw BusinessException(AuthErrorCode.MAIL_NOT_FOUND)

        if (Duration.between(existingMail.createdAt, Instant.now()) > EMAIL_VALID_TIME) {
            throw BusinessException(AuthErrorCode.MAIL_CODE_EXPIRED)
        }

        if (!existingMail.code.equals(code)) {
            throw BusinessException(AuthErrorCode.MAIL_CODE_MISMATCH)
        }

        existingMail.isVerified = true
    }
}

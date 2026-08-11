package com.dandi.nyummy.auth.service

import com.dandi.nyummy.auth.entity.Mail
import com.dandi.nyummy.auth.repository.MailRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
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
                throw RuntimeException("아직 5분이 지나지 않았습니다.")
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
            ?: throw RuntimeException("존재하지 않는 이메일입니다.")

        if (Duration.between(existingMail.createdAt, Instant.now()) > EMAIL_VALID_TIME) {
            throw RuntimeException("검증 시간이 지났습니다. 코드를 재발송 받으세요.")
        }

        if (!existingMail.code.equals(code)) {
            throw RuntimeException("코드가 다릅니다.")
        }

        existingMail.isVerified = true
    }
}

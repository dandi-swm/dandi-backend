package com.dandi.nyummy.user.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.Collections

@Service
class PasswordService(private val userRepository: UserRepository, private val passwordEncoder: PasswordEncoder) {

    companion object {
        private const val TEMP_PASSWORD_LENGTH = 12
        private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private const val DIGITS = "0123456789"
        private const val ALPHANUMERICS = LETTERS + DIGITS
        private val random = SecureRandom()
    }

    /**
     * 임시 비밀번호를 생성해 사용자 비밀번호를 교체하고, 생성된 평문 임시 비밀번호를 반환한다.
     *
     * 비밀번호 교체와 임시 비밀번호 상태 표시는 같은 트랜잭션에서 원자적으로 반영되며,
     * 반환된 평문은 호출부에서 이메일 발송에 사용된다.
     *
     * @param email 비밀번호를 교체할 사용자의 이메일
     * @return 생성된 평문 임시 비밀번호
     * @throws BusinessException [AuthErrorCode.EMAIL_NOT_FOUND] 이메일에 해당하는 사용자가 없는 경우
     */
    @Transactional
    fun createTempPasswordByEmail(email: String): String {
        val user = userRepository.findByProviderAndEmail(AuthProvider.EMAIL, email)
            ?: throw BusinessException(AuthErrorCode.EMAIL_NOT_FOUND)

        val tempPassword = createRandomTempPassword()

        user.updateTempPassword(encodePassword(tempPassword))

        return tempPassword
    }

    /**
     * 평문 비밀번호를 인코딩한다.
     *
     * @param rawPassword 평문 비밀번호
     * @return 인코딩된 비밀번호
     */
    fun encodePassword(rawPassword: String): String = checkNotNull(passwordEncoder.encode(rawPassword)) {
        "PasswordEncoder가 null을 반환했습니다."
    }

    /**
     * 평문 비밀번호가 인코딩된 비밀번호와 일치하는지 확인한다.
     *
     * 불일치 시 어떤 예외를 던질지는 호출부의 정책이므로(로그인 vs 비밀번호 변경) Boolean만 반환한다.
     *
     * @param rawPassword 평문 비밀번호
     * @param encodedPassword 인코딩된 비밀번호
     * @return 일치 여부
     */
    fun matchesPassword(rawPassword: String, encodedPassword: String): Boolean =
        passwordEncoder.matches(rawPassword, encodedPassword)

    /**
     * 자체 비밀번호 정책(8~64자, 영문·숫자 모두 포함)을 만족하는 임시 비밀번호를 생성한다.
     * 영문 1자와 숫자 1자를 먼저 넣어 정책을 보장하고, 나머지를 영숫자로 채운 뒤 섞는다.
     */
    private fun createRandomTempPassword(): String {
        val chars = mutableListOf(
            LETTERS[random.nextInt(LETTERS.length)],
            DIGITS[random.nextInt(DIGITS.length)],
        )

        repeat(TEMP_PASSWORD_LENGTH - 2) {
            chars.add(ALPHANUMERICS[random.nextInt(ALPHANUMERICS.length)])
        }

        Collections.shuffle(chars, random)

        return chars.joinToString("")
    }
}

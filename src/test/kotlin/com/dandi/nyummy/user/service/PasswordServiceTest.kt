package com.dandi.nyummy.user.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.springframework.security.crypto.password.PasswordEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PasswordServiceTest {

    // SignUpRequest.password의 @Pattern과 같은 정책 (영문·숫자 모두 포함)
    private val passwordPolicy = Regex("""^(?=.*[A-Za-z])(?=.*\d).*$""")
    private val alphanumeric12 = Regex("""^[A-Za-z0-9]{12}$""")

    private val email = "user@nyummy.com"
    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val passwordService = PasswordService(userRepository, passwordEncoder)

    private fun createUser() = User(email = email, password = "encoded:old")

    private fun stubEncoder() {
        every { passwordEncoder.encode(any()) } answers { "encoded:" + firstArg<String>() }
    }

    @Test
    fun `이메일에 해당하는 사용자가 없으면 EMAIL_NOT_FOUND다`() {
        every { userRepository.findByEmail(email) } returns null

        val exception = assertFailsWith<BusinessException> {
            passwordService.createTempPasswordByEmail(email)
        }

        assertEquals(AuthErrorCode.EMAIL_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `임시 비밀번호를 인코딩해 교체하고 임시 상태로 표시한 뒤 평문을 반환한다`() {
        val user = createUser()
        every { userRepository.findByEmail(email) } returns user
        stubEncoder()

        val tempPassword = passwordService.createTempPasswordByEmail(email)

        assertEquals("encoded:$tempPassword", user.password)
        assertTrue(user.isTempPassword)
        assertTrue(tempPassword.matches(alphanumeric12), "12자 영숫자여야 한다: $tempPassword")
        assertTrue(tempPassword.matches(passwordPolicy), "영문·숫자를 모두 포함해야 한다: $tempPassword")
    }

    @Test
    fun `임시 비밀번호는 항상 비밀번호 정책을 만족한다`() {
        every { userRepository.findByEmail(email) } returns createUser()
        stubEncoder()

        repeat(200) {
            val tempPassword = passwordService.createTempPasswordByEmail(email)

            assertTrue(tempPassword.matches(alphanumeric12), "12자 영숫자여야 한다: $tempPassword")
            assertTrue(
                tempPassword.matches(passwordPolicy),
                "영문·숫자를 모두 포함해야 한다: $tempPassword",
            )
        }
    }

    @Test
    fun `인코더가 null을 반환하면 IllegalStateException이다`() {
        every { passwordEncoder.encode(any()) } returns null

        assertFailsWith<IllegalStateException> {
            passwordService.encodePassword("password1")
        }
    }
}

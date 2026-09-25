package com.dandi.nyummy.auth.service

import com.dandi.nyummy.auth.entity.Code
import com.dandi.nyummy.auth.enum.AuthPurpose
import com.dandi.nyummy.auth.repository.CodeRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodeServiceTest {

    private val now = Instant.parse("2026-09-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val challengeTimeToLive = Duration.ofMinutes(5)
    private val email = "user@nyummy.com"

    private val codeRepository = mockk<CodeRepository>()
    private val codeService = CodeService(codeRepository, clock, challengeTimeToLive)

    private fun createCode(
        code: String = "123456",
        expiresAt: Instant = now.plus(challengeTimeToLive),
        type: AuthPurpose = AuthPurpose.SIGNUP,
        sendCount: Int = 1,
        attemptCount: Int = 0,
    ) = Code(email = email, code = code, expiresAt = expiresAt, type = type).apply {
        this.sendCount = sendCount
        this.attemptCount = attemptCount
    }

    private fun assertBusinessException(expected: AuthErrorCode, block: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = block)
        assertEquals(expected, exception.errorCode)
    }

    // createCodeByEmail

    @Test
    fun `기존 코드가 없으면 새 코드를 생성해 저장한다`() {
        every { codeRepository.findByEmail(email) } returns null
        val saved = slot<Code>()
        every { codeRepository.save(capture(saved)) } answers { firstArg() }

        val result = codeService.createCodeByEmail(email, AuthPurpose.SIGNUP)

        assertTrue(result.matches(Regex("""^\d{6}$""")), "6자리 숫자여야 한다: $result")
        assertEquals(email, saved.captured.email)
        assertEquals(result, saved.captured.code)
        assertEquals(AuthPurpose.SIGNUP, saved.captured.type)
        assertEquals(now.plus(challengeTimeToLive), saved.captured.expiresAt)
        assertEquals(1, saved.captured.sendCount)
        assertEquals(0, saved.captured.attemptCount)
    }

    @Test
    fun `발송 윈도우 안이면 코드와 용도만 교체하고 윈도우는 유지한다`() {
        val windowEnd = now.plusSeconds(120)
        val existing = createCode(code = "111111", expiresAt = windowEnd, sendCount = 2, attemptCount = 3)
        every { codeRepository.findByEmail(email) } returns existing
        every { codeRepository.save(existing) } returns existing

        val result = codeService.createCodeByEmail(email, AuthPurpose.RESET_PASSWORD)

        assertEquals(result, existing.code)
        assertEquals(AuthPurpose.RESET_PASSWORD, existing.type)
        assertEquals(3, existing.sendCount)
        assertEquals(0, existing.attemptCount)
        assertEquals(windowEnd, existing.expiresAt)
    }

    @Test
    fun `발송 윈도우가 지났으면 발송 횟수를 초기화하고 윈도우를 새로 연다`() {
        val existing = createCode(expiresAt = now.minusSeconds(1), sendCount = 2)
        every { codeRepository.findByEmail(email) } returns existing
        every { codeRepository.save(existing) } returns existing

        codeService.createCodeByEmail(email, AuthPurpose.SIGNUP)

        assertEquals(1, existing.sendCount)
        assertEquals(now.plus(challengeTimeToLive), existing.expiresAt)
    }

    @Test
    fun `발송 윈도우 안에서 5회를 채웠으면 발송을 거부하고 저장하지 않는다`() {
        val existing = createCode(sendCount = 5)
        every { codeRepository.findByEmail(email) } returns existing

        assertBusinessException(AuthErrorCode.EMAIL_SEND_RATE_LIMITED) {
            codeService.createCodeByEmail(email, AuthPurpose.SIGNUP)
        }
        verify(exactly = 0) { codeRepository.save(any()) }
    }

    @Test
    fun `발송 윈도우 안에서 4회까지 보냈으면 5번째 발송은 허용한다`() {
        val existing = createCode(sendCount = 4)
        every { codeRepository.findByEmail(email) } returns existing
        every { codeRepository.save(existing) } returns existing

        codeService.createCodeByEmail(email, AuthPurpose.SIGNUP)

        assertEquals(5, existing.sendCount)
    }

    @Test
    fun `5회를 채웠어도 발송 윈도우가 지났으면 다시 발송할 수 있다`() {
        val existing = createCode(expiresAt = now.minusSeconds(1), sendCount = 5)
        every { codeRepository.findByEmail(email) } returns existing
        every { codeRepository.save(existing) } returns existing

        codeService.createCodeByEmail(email, AuthPurpose.SIGNUP)

        assertEquals(1, existing.sendCount)
    }

    // confirmAuthCodeByEmail

    @Test
    fun `이메일로 발급된 코드가 없으면 INCORRECT_EMAIL이다`() {
        every { codeRepository.findByEmail(email) } returns null

        assertBusinessException(AuthErrorCode.INCORRECT_EMAIL) {
            codeService.confirmAuthCodeByEmail("123456", email, AuthPurpose.SIGNUP)
        }
    }

    @Test
    fun `발급 용도가 다르면 코드가 없는 것으로 보고 시도 횟수를 올리지 않는다`() {
        val existing = createCode(type = AuthPurpose.SIGNUP, attemptCount = 2)
        every { codeRepository.findByEmail(email) } returns existing

        assertBusinessException(AuthErrorCode.INCORRECT_EMAIL) {
            codeService.confirmAuthCodeByEmail("123456", email, AuthPurpose.RESET_PASSWORD)
        }
        assertEquals(2, existing.attemptCount)
        verify(exactly = 0) { codeRepository.delete(any()) }
    }

    @Test
    fun `오답이 5회 누적되면 정답이어도 차단한다`() {
        val existing = createCode(code = "123456", attemptCount = 5)
        every { codeRepository.findByEmail(email) } returns existing

        assertBusinessException(AuthErrorCode.EMAIL_CODE_ATTEMPT_EXCEEDED) {
            codeService.confirmAuthCodeByEmail("123456", email, AuthPurpose.SIGNUP)
        }
        verify(exactly = 0) { codeRepository.delete(any()) }
    }

    @Test
    fun `코드가 틀리면 시도 횟수를 올리고 코드는 남긴다`() {
        val existing = createCode(code = "123456", attemptCount = 0)
        every { codeRepository.findByEmail(email) } returns existing

        assertBusinessException(AuthErrorCode.EMAIL_CODE_MISMATCH) {
            codeService.confirmAuthCodeByEmail("000000", email, AuthPurpose.SIGNUP)
        }
        assertEquals(1, existing.attemptCount)
        verify(exactly = 0) { codeRepository.delete(any()) }
    }

    @Test
    fun `코드가 맞으면 재사용을 막기 위해 삭제한다`() {
        val existing = createCode(code = "123456")
        every { codeRepository.findByEmail(email) } returns existing
        every { codeRepository.delete(existing) } just Runs

        codeService.confirmAuthCodeByEmail("123456", email, AuthPurpose.SIGNUP)

        verify(exactly = 1) { codeRepository.delete(existing) }
    }

    @Test
    fun `오답 4회 뒤의 정답은 통과한다`() {
        val existing = createCode(code = "123456", attemptCount = 4)
        every { codeRepository.findByEmail(email) } returns existing
        every { codeRepository.delete(existing) } just Runs

        codeService.confirmAuthCodeByEmail("123456", email, AuthPurpose.SIGNUP)

        verify(exactly = 1) { codeRepository.delete(existing) }
    }
}

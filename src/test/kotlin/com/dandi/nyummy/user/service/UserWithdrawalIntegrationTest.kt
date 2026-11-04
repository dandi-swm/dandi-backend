package com.dandi.nyummy.user.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.infra.push.dto.PushType
import com.dandi.nyummy.notification.entity.PushHistory
import com.dandi.nyummy.notification.enum.PushOutcome
import com.dandi.nyummy.notification.repository.PushHistoryRepository
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 탈퇴를 실제 MySQL에서 돌린다. 벌크 삭제는 영속성 컨텍스트를 비우므로, 그 전에 바꾼 사용자 상태가 함께 저장되는지는 mock으로 확인할 수 없다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class UserWithdrawalIntegrationTest @Autowired constructor(
    private val userWithdrawalTransactionService: UserWithdrawalTransactionService,
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val pushHistoryRepository: PushHistoryRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")
    }

    @Test
    fun `탈퇴하면 본인 전송 내역만 지워지고 탈퇴 상태는 저장된다`() {
        val userId = createUser()
        val otherUserId = createUser()
        createPushHistory(userId)
        createPushHistory(otherUserId)

        userWithdrawalTransactionService.withdrawUser(userId)

        val remainingUserIds = pushHistoryRepository.findAll().map { it.userId }
        assertTrue(userId !in remainingUserIds)
        assertEquals(1, remainingUserIds.count { it == otherUserId })
        assertTrue(userRepository.findByIdOrNull(userId)!!.isWithdrawn)
    }

    private fun createUser(): Long {
        val userId = userRepository.save(
            User(provider = AuthProvider.EMAIL, email = "${UUID.randomUUID()}@nyummy.co.kr"),
        ).id
        profileRepository.save(Profile(userId = userId))

        return userId
    }

    private fun createPushHistory(userId: Long) {
        pushHistoryRepository.save(
            PushHistory(
                userId = userId,
                notificationId = "inquiry-answer:$userId",
                category = PushType.INQUIRY_ANSWERED,
                platform = null,
                title = "문의에 답변이 왔다냥",
                body = "답변 제목",
                outcome = PushOutcome.SUCCESS,
                sentAt = Instant.now(),
            ),
        )
    }
}

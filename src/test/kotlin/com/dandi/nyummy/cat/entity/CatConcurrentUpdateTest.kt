package com.dandi.nyummy.cat.entity

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 고양이 행을 여러 경로가 동시에 써도 서로의 컬럼을 되돌리지 않는지 실제 MySQL로 확인한다.
 * 어떤 컬럼을 UPDATE하는지는 Hibernate 매핑이 정하므로 mock으로는 확인할 수 없다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CatConcurrentUpdateTest @Autowired constructor(
    private val catService: CatService,
    private val catRepository: CatRepository,
    private val userRepository: UserRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")

        private const val TIMEOUT_SECONDS = 30L
    }

    @Test
    fun `체형을 평가하는 사이 Worker가 마지막 식사 시각을 바꿔도 그 시각이 남는다`() {
        val userId = createUserWithCat("cat-concurrent-update@nyummy.co.kr")
        val lastMealAt = Instant.parse("2026-11-03T03:00:00Z")
        val evaluatedAt = Instant.parse("2026-11-03T15:00:00Z")
        val executor = Executors.newSingleThreadExecutor()

        // 고양이 조회와 같은 순서다. 체형 평가 도중에 멈출 수 없어 행을 읽은 뒤 Worker를 끼워 넣고 직접 체형을 바꾼다.
        try {
            transactionTemplate.executeWithoutResult {
                val cat = catRepository.findByUserId(userId)!!

                executor.submit {
                    catService.updateLastMealAt(userId, lastMealAt)
                }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

                cat.updateWeightByStep(-1, evaluatedAt)
            }
        } finally {
            executor.shutdown()
        }

        val cat = catRepository.findByUserId(userId)!!
        assertEquals(lastMealAt, cat.lastMealAt)
        assertEquals(-1, cat.weight)
    }

    private fun createUserWithCat(email: String): Long {
        val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = email)).id
        catRepository.save(Cat(name = "나비", userId = userId))
        return userId
    }
}

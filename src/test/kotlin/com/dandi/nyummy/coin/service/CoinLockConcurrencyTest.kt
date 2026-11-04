package com.dandi.nyummy.coin.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.coin.dto.ClaimCoinGiftRequest
import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.repository.CoinHistoryRepository
import com.dandi.nyummy.coin.repository.CoinRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CoinErrorCode
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.CannotAcquireLockException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 코인 행 잠금이 실제 MySQL에서 기대대로 동작하는지 확인한다.
 * 잠금 대기 시간과 교착은 mock으로 재현할 수 없고, 설정 키를 잘못 적어도 다른 테스트는 통과한다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CoinLockConcurrencyTest @Autowired constructor(
    private val coinService: CoinService,
    private val coinGiftService: CoinGiftService,
    private val coinHistoryRepository: CoinHistoryRepository,
    private val mailService: MailService,
    private val userRepository: UserRepository,
    private val coinRepository: CoinRepository,
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
    fun `다른 트랜잭션이 코인 행을 잠그고 있으면 약 3초 기다린 뒤 실패한다`() {
        val userId = createUserWithCoin("lock-wait@nyummy.co.kr")
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val holder = executor.submit {
            transactionTemplate.executeWithoutResult {
                coinService.lockCoin(userId)
                locked.countDown()
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }

        try {
            assertTrue(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            assertFailsWith<CannotAcquireLockException> {
                transactionTemplate.executeWithoutResult { coinService.lockCoin(userId) }
            }
            val waited = Duration.ofNanos(System.nanoTime() - startedAt)

            // InnoDB는 시간 초과를 주기적으로 확인해 3초를 조금 넘길 수 있다. 상한은 기본값 50초와 구분할 만큼만 둔다.
            assertTrue(waited >= Duration.ofSeconds(2) && waited < Duration.ofSeconds(10), "대기 시간: $waited")
        } finally {
            release.countDown()
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            executor.shutdown()
        }
    }

    @Test
    fun `트랜잭션 없이 코인 행을 잠그면 실패한다`() {
        val userId = createUserWithCoin("lock-no-transaction@nyummy.co.kr")

        assertFailsWith<IllegalTransactionStateException> { coinService.lockCoin(userId) }
    }

    @Test
    fun `코인을 잠그고 우편을 만드는 트랜잭션과 모두 받기가 겹쳐도 교착 없이 둘 다 끝난다`() {
        val userId = createUserWithCoin("lock-order@nyummy.co.kr")
        createMail(userId)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // 구매와 같은 순서다. 구매 서비스로는 코인 잠금과 우편 INSERT 사이에서 멈출 수 없어 같은 순서를 직접 쓴다.
        val creator = executor.submit {
            transactionTemplate.executeWithoutResult {
                coinService.lockCoin(userId)
                locked.countDown()
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                createMail(userId)
            }
        }
        assertTrue(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val claimer = executor.submit { mailService.claimMails(userId) }

        // 모두 받기가 잠금 대기에 들어갈 시간을 준다. 우편을 먼저 잠그는 코드면 이때 갭 잠금을 쥐고 코인을 기다려
        // 아래 INSERT와 교착이 난다. 시간이 모자라면 교착을 놓칠 뿐 코인을 먼저 잠그는 코드가 실패하지는 않는다.
        Thread.sleep(500)
        release.countDown()

        creator.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        claimer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        executor.shutdown()

        // 잠금 뒤에 조회하므로 앞 트랜잭션이 만든 우편까지 받는다.
        assertEquals(20, coinRepository.findByUserId(userId)?.balance)
    }

    @Test
    fun `선물 코인을 동시에 두 번 요청해도 한 번만 받고 나머지는 거부한다`() {
        val userId = createUserWithCoin("coin-gift@nyummy.co.kr")
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        val holder = executor.submit {
            transactionTemplate.executeWithoutResult {
                coinService.lockCoin(userId)
                locked.countDown()
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        assertTrue(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val requests = List(2) {
            executor.submit<Result<Int>> {
                runCatching {
                    coinGiftService.claimCoinGift(userId, "A", ClaimCoinGiftRequest(coinPackageId = 1)).balance
                }
            }
        }

        // 두 요청이 잠금 대기에 들어갈 시간을 준다. 잠금 없이 내역부터 읽는 코드면 둘 다 받은 적이 없다고 보고 두 번 넣는다.
        Thread.sleep(500)
        release.countDown()

        holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val results = requests.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        executor.shutdown()

        assertEquals(listOf(3000), results.mapNotNull { it.getOrNull() })
        val rejected = results.mapNotNull { it.exceptionOrNull() }.single() as BusinessException
        assertEquals(CoinErrorCode.COIN_GIFT_ALREADY_CLAIMED, rejected.errorCode)
        assertEquals(3000, coinRepository.findByUserId(userId)?.balance)
        assertEquals(1, coinHistoryRepository.findAll().count { it.userId == userId })
    }

    private fun createUserWithCoin(email: String): Long {
        val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = email)).id
        coinRepository.save(Coin(userId = userId))
        return userId
    }

    private fun createMail(userId: Long) {
        mailService.createRewardBoxMail(
            userId = userId,
            rewardBoxCategory = RewardBoxCategory.BRONZE,
            coinQuantity = 10,
            content = "테스트 보상",
            expiresAt = null,
        )
    }
}

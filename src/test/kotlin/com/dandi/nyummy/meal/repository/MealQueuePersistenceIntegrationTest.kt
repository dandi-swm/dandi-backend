package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.meal.entity.MealAnalysisQueue
import com.dandi.nyummy.meal.entity.MealOutbox
import com.dandi.nyummy.meal.enum.MealAnalysisQueueStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class MealQueuePersistenceIntegrationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")
    }

    @Autowired
    private lateinit var outboxes: MealOutboxRepository

    @Autowired
    private lateinit var queue: MealAnalysisQueueRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var transaction: TransactionTemplate

    private val now = Instant.parse("2026-10-04T00:00:00Z")

    @BeforeEach
    fun clean() {
        transaction = TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        }
        queue.deleteAllInBatch()
        outboxes.deleteAllInBatch()
    }

    @Test
    fun `미전달 Outbox만 ID 순서와 배치 크기에 맞게 조회한다`() {
        val first = outboxes.save(MealOutbox(1, now))
        val published = outboxes.save(MealOutbox(2, now))
        val last = outboxes.save(MealOutbox(3, now))

        transaction.executeWithoutResult {
            assertNotNull(outboxes.findByIdForUpdate(published.id)).markPublished(now)
        }

        assertEquals(listOf(first.id), outboxes.findPendingIds(1))
        assertEquals(listOf(first.id, last.id), outboxes.findPendingIds(10))
        assertEquals(now, outboxes.findById(published.id).orElseThrow().publishedAt)
    }

    @Test
    fun `같은 Outbox는 큐에 중복 저장할 수 없다`() {
        val outbox = outboxes.save(MealOutbox(1, now))
        queue.saveAndFlush(MealAnalysisQueue(outbox.id, outbox.mealId, now))

        assertFailsWith<DataIntegrityViolationException> {
            queue.saveAndFlush(MealAnalysisQueue(outbox.id, outbox.mealId, now))
        }
        assertEquals(1L, queue.count())
    }

    @Test
    fun `큐 적재와 Outbox 전달 완료 변경은 함께 롤백된다`() {
        val outbox = outboxes.save(MealOutbox(1, now))

        transaction.executeWithoutResult { status ->
            val locked = assertNotNull(outboxes.findByIdForUpdate(outbox.id))
            queue.saveAndFlush(MealAnalysisQueue(locked.id, locked.mealId, now))
            locked.markPublished(now)
            outboxes.flush()
            status.setRollbackOnly()
        }

        assertEquals(0L, queue.count())
        assertNull(outboxes.findById(outbox.id).orElseThrow().publishedAt)
    }

    @Test
    fun `다른 트랜잭션이 잠근 작업을 건너뛰고 커밋된 선점은 다음 조회에서 제외된다`() {
        val first = saveJob(1)
        val second = saveJob(2)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val firstClaim = executor.submit<Long> {
                transaction.execute {
                    val job = queue.findReadyForUpdate(1).single()
                    assertEquals(first.id, job.id)
                    job.start(now)
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    job.id
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))

            val secondClaim = executor.submit<Long> {
                transaction.execute {
                    val job = queue.findReadyForUpdate(1).single()
                    job.start(now)
                    job.id
                }
            }
            // 첫 트랜잭션의 락 해제를 기다리지 않고 두 번째 작업을 가져와야 한다.
            assertEquals(second.id, secondClaim.get(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(first.id, firstClaim.get(5, TimeUnit.SECONDS))

            transaction.executeWithoutResult {
                assertTrue(queue.findReadyForUpdate(2).isEmpty())
                assertNotNull(queue.findByIdForUpdate(first.id)).complete(now.plusSeconds(1))
                assertNotNull(queue.findByIdForUpdate(second.id)).fail(now.plusSeconds(2))
            }

            val completed = queue.findById(first.id).orElseThrow()
            val failed = queue.findById(second.id).orElseThrow()
            assertEquals(MealAnalysisQueueStatus.DONE, completed.status)
            assertEquals(now, completed.startedAt)
            assertEquals(now.plusSeconds(1), completed.finishedAt)
            assertEquals(MealAnalysisQueueStatus.FAILED, failed.status)
            assertEquals(now.plusSeconds(2), failed.finishedAt)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private fun saveJob(mealId: Long): MealAnalysisQueue {
        val outbox = outboxes.save(MealOutbox(mealId, now))
        return queue.saveAndFlush(MealAnalysisQueue(outbox.id, mealId, now))
    }
}

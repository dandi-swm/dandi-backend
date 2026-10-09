package com.dandi.nyummy.meal.service

import com.dandi.nyummy.meal.config.MealOutboxProperties
import com.dandi.nyummy.meal.entity.MealOutbox
import com.dandi.nyummy.meal.enum.MealAnalysisQueueStatus
import com.dandi.nyummy.meal.event.MealAnalysisRequested
import com.dandi.nyummy.meal.queue.DbMealAnalysisPublisher
import com.dandi.nyummy.meal.queue.MealAnalysisMessage
import com.dandi.nyummy.meal.repository.MealAnalysisQueueRepository
import com.dandi.nyummy.meal.repository.MealOutboxRepository
import com.dandi.nyummy.meal.scheduling.MealOutboxRecoveryScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class OutboxDispatchIntegrationTest {
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
    private lateinit var mealService: MealService

    @Autowired
    private lateinit var events: ApplicationEventPublisher

    @Autowired
    private lateinit var transaction: TransactionTemplate

    @Autowired
    @Qualifier("outboxExecutor")
    private lateinit var executor: ThreadPoolTaskExecutor

    @MockitoSpyBean
    private lateinit var publisher: DbMealAnalysisPublisher

    private lateinit var recovery: MealOutboxRecoveryScheduler

    private fun anyMessage(): MealAnalysisMessage = any(MealAnalysisMessage::class.java) ?: MealAnalysisMessage(0, 0)

    @BeforeEach
    fun setUp() {
        awaitAsyncDispatches()
        queue.deleteAllInBatch()
        outboxes.deleteAllInBatch()
        recovery = MealOutboxRecoveryScheduler(outboxes, mealService, MealOutboxProperties())
    }

    @AfterEach
    fun awaitAsyncDispatches() {
        // 단일 스레드 Executor의 앞선 리스너 작업이 커밋까지 마치도록 기다린다.
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `커밋 전에는 전달하지 않고 커밋 후 전용 스레드에서 READY 작업을 저장한다`() {
        val executionThread = AtomicReference<String>()
        doAnswer { invocation ->
            executionThread.set(Thread.currentThread().name)
            invocation.callRealMethod()
        }.`when`(AopTestUtils.getUltimateTargetObject<DbMealAnalysisPublisher>(publisher)).publish(anyMessage())

        val id = transaction.execute {
            val outbox = outboxes.save(MealOutbox(1, Instant.now()))
            events.publishEvent(MealAnalysisRequested(outbox.id))
            awaitAsyncDispatches()
            assertNull(executionThread.get())
            assertEquals(0L, queue.count())
            outbox.id
        }
        awaitAsyncDispatches()

        assertTrue(executionThread.get().startsWith("meal-outbox-"))
        val job = queue.findAll().single()
        assertEquals(id, job.outboxId)
        assertEquals(1L, job.mealId)
        assertEquals(MealAnalysisQueueStatus.READY, job.status)
        assertNotNull(outboxes.findById(id).orElseThrow().publishedAt)
    }

    @Test
    fun `롤백된 트랜잭션의 이벤트는 전달하지 않는다`() {
        transaction.executeWithoutResult { status ->
            val outbox = outboxes.save(MealOutbox(1, Instant.now()))
            events.publishEvent(MealAnalysisRequested(outbox.id))
            status.setRollbackOnly()
        }
        awaitAsyncDispatches()

        assertEquals(0L, outboxes.count())
        assertEquals(0L, queue.count())
    }

    @Test
    fun `이벤트 없이 남은 Outbox를 배치 크기만큼 복구한다`() {
        val first = outboxes.save(MealOutbox(1, Instant.now()))
        val second = outboxes.save(MealOutbox(2, Instant.now()))
        val singleBatch = MealOutboxRecoveryScheduler(outboxes, mealService, MealOutboxProperties(batchSize = 1))

        singleBatch.recover()
        assertEquals(listOf(first.id), queue.findAll().map { it.outboxId })
        assertEquals(listOf(second.id), outboxes.findPendingIds(100))

        singleBatch.recover()
        assertEquals(2L, queue.count())
        assertTrue(outboxes.findPendingIds(100).isEmpty())
    }

    @Test
    fun `리스너 전달 실패는 큐 삽입도 롤백하고 다음 복구에서 전달한다`() {
        val fail = AtomicBoolean(true)
        doAnswer { invocation ->
            invocation.callRealMethod()
            if (fail.getAndSet(false)) error("큐 저장 후 전달 실패")
            null
        }.`when`(AopTestUtils.getUltimateTargetObject<DbMealAnalysisPublisher>(publisher)).publish(anyMessage())

        val id = transaction.execute {
            val outbox = outboxes.save(MealOutbox(1, Instant.now()))
            events.publishEvent(MealAnalysisRequested(outbox.id))
            outbox.id
        }
        awaitAsyncDispatches()

        assertEquals(0L, queue.count())
        assertNull(outboxes.findById(id).orElseThrow().publishedAt)

        recovery.recover()

        assertEquals(1L, queue.count())
        assertNotNull(outboxes.findById(id).orElseThrow().publishedAt)
    }

    @Test
    fun `배치 중 한 건이 실패해도 다음 건은 전달한다`() {
        val failed = outboxes.save(MealOutbox(1, Instant.now()))
        val next = outboxes.save(MealOutbox(2, Instant.now()))
        doAnswer { invocation ->
            val message = invocation.getArgument<MealAnalysisMessage>(0)
            if (message.eventId == failed.id) error("전달 실패")
            invocation.callRealMethod()
        }.`when`(AopTestUtils.getUltimateTargetObject<DbMealAnalysisPublisher>(publisher)).publish(anyMessage())

        recovery.recover()

        assertEquals(listOf(next.id), queue.findAll().map { it.outboxId })
        assertEquals(listOf(failed.id), outboxes.findPendingIds(100))
        assertNotNull(outboxes.findById(next.id).orElseThrow().publishedAt)
    }

    @Test
    fun `비동기 실행 풀이 가득 차 이벤트 제출이 거부되어도 Outbox를 복구한다`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = executor.submit {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val waiting = (1..executor.queueCapacity).map { executor.submit {} }

            val id = transaction.execute {
                val outbox = outboxes.save(MealOutbox(1, Instant.now()))
                events.publishEvent(MealAnalysisRequested(outbox.id))
                outbox.id
            }

            assertEquals(0L, queue.count())
            assertNull(outboxes.findById(id).orElseThrow().publishedAt)
            recovery.recover()
            assertEquals(1L, queue.count())
            assertNotNull(outboxes.findById(id).orElseThrow().publishedAt)

            release.countDown()
            blocked.get(5, TimeUnit.SECONDS)
            waiting.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            blocked.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `동시 전달과 복구 및 중복 이벤트에도 큐에는 한 건만 저장한다`() {
        val outbox = outboxes.save(MealOutbox(1, Instant.now()))
        val start = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val direct = threads.submit {
                check(start.await(5, TimeUnit.SECONDS))
                mealService.dispatchMealOutbox(outbox.id)
            }
            val scheduled = threads.submit {
                check(start.await(5, TimeUnit.SECONDS))
                recovery.recover()
            }
            start.countDown()
            direct.get(10, TimeUnit.SECONDS)
            scheduled.get(10, TimeUnit.SECONDS)

            transaction.executeWithoutResult {
                events.publishEvent(MealAnalysisRequested(outbox.id))
                events.publishEvent(MealAnalysisRequested(outbox.id))
            }
            awaitAsyncDispatches()

            assertEquals(1L, queue.count())
            assertNotNull(outboxes.findById(outbox.id).orElseThrow().publishedAt)
        } finally {
            start.countDown()
            threads.shutdownNow()
            threads.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}

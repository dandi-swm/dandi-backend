package com.dandi.nyummy.report

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.repository.CoinRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.ReportErrorCode
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.report.dto.CreateReportPurchaseRequest
import com.dandi.nyummy.report.service.ReportCancelService
import com.dandi.nyummy.report.service.ReportPurchaseService
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import com.dandi.nyummy.worker.dto.ReportGenerationJobResult
import com.dandi.nyummy.worker.service.ReportGenerationJobTransactionService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * 리포트 하나를 두고 Worker 둘과 사용자 취소 둘이 동시에 부딪히는 경쟁을 실제 MySQL에서 [rounds]번 반복하고,
 * 돈과 상태의 규칙이 한 번도 깨지지 않는지 센다. 장치(잠금·조건부 전이·lease 번호 대조·유니크 제약)를 하나씩 뺀 코드로
 * 같은 테스트를 돌려(ablation) 각 장치가 무엇을 막는지 확인하는 데도 쓴다.
 *
 * 한 라운드:
 * - Worker A: 작업을 lease로 가져가 확정하거나 실패(환불) 처리한다.
 * - Worker B: A의 lease를 만료시키고 다시 가져가 확정한다(A가 늦게 끝난 Worker가 되는 상황).
 * - 사용자 취소 두 번(따닥).
 *
 * 세는 것:
 * - 데드락·잠금 대기 초과, 유니크 제약이 막은 쓰기, 그 밖의 예상하지 못한 오류
 * - 이중 환불(환불 행 2개 이상), 환불 뒤 전달(완료된 결과가 있는데 환불됨), 잔액 불일치(구매·환불과 맞지 않음)
 * - 늦게 끝난 Worker의 쓰기(B가 다시 가져갔는데 A의 결과가 남음), 끝나지 않고 남은 리포트·작업
 *
 * 실제 MySQL로 오래 돌아 기본 test에서 빠진다. `./gradlew raceTest -PraceRounds=1000`으로 돌린다.
 */
@Tag("race")
@Testcontainers
@SpringBootTest(
    properties = [
        "app.worker.enabled=true",
        "app.worker.analysis.enabled=false",
        "app.worker.report.enabled=true",
        // 폴러가 끼어들지 않게 한다. Worker의 트랜잭션은 테스트가 직접 부른다.
        "app.worker.report.polling-delay=1h",
    ],
)
@ActiveProfiles("test")
class ReportConsistencyRaceTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql: MySQLContainer = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")

        private const val INITIAL_BALANCE = 10_000
        private const val LIST_PRICE = 300
        private const val PAID_ROUND_RATE = 80
        private const val MAX_JITTER_MILLIS = 20L


        private val SUNDAY: LocalDate = LocalDate.parse("2026-10-25")

        private val pool = Executors.newFixedThreadPool(4)

        @AfterAll
        @JvmStatic
        fun shutdownPool() {
            pool.shutdownNow()
        }
    }

    private val rounds = System.getProperty("raceRounds")?.toInt() ?: 200

    // 취소가 출발하기 전 최대 지연. 짧으면(20) 취소와 Worker의 쓰기가 같은 순간에 겹쳐 데드락 같은 문제가 잘 드러나고,
    // 길면(60) 취소가 늦게 끝나는 라운드가 많아져 완료·실패 쪽 경쟁이 고르게 나온다.
    private val maxCancelJitterMillis = System.getProperty("raceCancelJitterMillis")?.toLong() ?: 60L

    // ablation용. true면 시작 전에 환불 유니크 제약(구매당 환불 한 번)을 지운다.
    private val isRefundUniqueDropped = System.getProperty("raceDropRefundUnique").toBoolean()

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var coinRepository: CoinRepository

    @Autowired lateinit var mealRepository: MealRepository

    @Autowired lateinit var reportPurchaseService: ReportPurchaseService

    @Autowired lateinit var reportCancelService: ReportCancelService

    @Autowired lateinit var reportGenerationJobTransactionService: ReportGenerationJobTransactionService

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    /** 한 라운드에서 일어난 일 */
    private data class RoundResult(val finalStatus: String, val outcomes: List<String>, val violations: List<String>)

    @Test
    fun `Worker 둘과 취소 둘이 겹쳐도 이중 환불·환불 뒤 전달·잔액 불일치·늦은 쓰기·데드락이 없다`() {
        if (isRefundUniqueDropped) dropRefundUnique()
        val results = (1..rounds).map { runRound() }

        val statusCounts = results.groupingBy { it.finalStatus }.eachCount()
        val outcomeCounts = results.flatMap { it.outcomes }.groupingBy { it }.eachCount()
        val violationCounts = results.flatMap { it.violations }.groupingBy { it }.eachCount()
        val violatedRounds = results.count { it.violations.isNotEmpty() }

        println(
            "RACE_SUMMARY rounds=$rounds cancelJitter=${maxCancelJitterMillis}ms " +
                "refundUniqueDropped=$isRefundUniqueDropped finalStatus=$statusCounts",
        )
        println("RACE_SUMMARY outcomes=$outcomeCounts")
        println("RACE_SUMMARY violations=$violationCounts violatedRounds=$violatedRounds")
        printLatestDeadlock()

        assertEquals(emptyMap(), violationCounts, "규칙이 깨진 라운드: $violatedRounds/$rounds")
    }

    private fun runRound(): RoundResult {
        val isPaid = Random.nextInt(100) < PAID_ROUND_RATE
        val userId = createUser(isPaid)
        val reportId = reportPurchaseService.createReportPurchase(
            userId,
            UUID.randomUUID().toString(),
            CreateReportPurchaseRequest(SUNDAY, if (isPaid) LIST_PRICE else 0),
            null,
        ).reportId

        val outcomes = mutableListOf<String>()
        val workerAWrote = AtomicReference(false)
        val workerBLeased = AtomicReference(false)
        val start = CountDownLatch(1)

        fun task(name: String, maxJitterMillis: Long = MAX_JITTER_MILLIS, block: () -> Unit) = pool.submit<String> {
            start.await()
            Thread.sleep(Random.nextLong(maxJitterMillis))
            try {
                block()
                "$name:ok"
            } catch (e: BusinessException) {
                if (e.errorCode ==
                    ReportErrorCode.NOT_CANCELABLE
                ) {
                    "$name:notCancelable"
                } else {
                    "$name:error:${e.errorCode}"
                }
            } catch (e: PessimisticLockingFailureException) {
                if (e.mostSpecificCause.message.orEmpty().contains(
                        "Deadlock",
                    )
                ) {
                    "$name:deadlock"
                } else {
                    "$name:lockTimeout"
                }
            } catch (e: DataIntegrityViolationException) {
                "$name:uniqueBlocked"
            } catch (e: RuntimeException) {
                "$name:error:${e::class.simpleName}"
            }
        }

        val tasks = listOf(
            task("workerA") {
                val job = lease() ?: return@task
                Thread.sleep(Random.nextLong(MAX_JITTER_MILLIS))
                val written = if (Random.nextBoolean()) {
                    reportGenerationJobTransactionService.confirmReportGeneration(job, "A", "NutritionBalance", "{}")
                } else {
                    reportGenerationJobTransactionService.failReportGeneration(job)
                }
                if (written != null) workerAWrote.set(true)
            },
            task("workerB") {
                // A가 가져간 lease가 만료된 것처럼 만든 뒤 다시 가져간다. 운영 코드처럼 PK로 바꾼다
                // (report_id로 바꾸면 이 흉내 자체가 취소의 PK 삭제와 인덱스를 거꾸로 잠가 데드락을 만든다).
                jdbcTemplate.queryForList(
                    "SELECT id FROM report_generation_jobs WHERE report_id = ?",
                    Long::class.java,
                    reportId,
                ).forEach { jobId ->
                    jdbcTemplate.update(
                        "UPDATE report_generation_jobs SET lease_expires_at = NOW() - INTERVAL 1 SECOND WHERE id = ?",
                        jobId,
                    )
                }
                val job = lease() ?: return@task
                workerBLeased.set(true)
                Thread.sleep(Random.nextLong(MAX_JITTER_MILLIS))
                reportGenerationJobTransactionService.confirmReportGeneration(job, "B", "NutritionBalance", "{}")
            },
            task("cancel1", maxCancelJitterMillis) { reportCancelService.cancelReport(userId, reportId) },
            task("cancel2", maxCancelJitterMillis) { reportCancelService.cancelReport(userId, reportId) },
        )
        start.countDown()
        tasks.forEach { outcomes += it.get(2, TimeUnit.MINUTES) }

        return RoundResult(
            finalStatus = status(reportId),
            outcomes = outcomes.filterNot { it.endsWith(":ok") },
            violations = checkRound(userId, reportId, isPaid, workerAWrote.get(), workerBLeased.get(), outcomes),
        )
    }

    private fun checkRound(
        userId: Long,
        reportId: Long,
        isPaid: Boolean,
        workerAWrote: Boolean,
        workerBLeased: Boolean,
        outcomes: List<String>,
    ): List<String> {
        val violations = mutableListOf<String>()
        val status = status(reportId)
        val refundCount = count(
            """
            SELECT COUNT(*) FROM report_purchase_refunds rf
                JOIN report_purchases p ON p.id = rf.report_purchase_id
            WHERE p.report_id = ?
            """,
            reportId,
        )
        val hasResult = count("SELECT COUNT(*) FROM reports WHERE id = ? AND completed_at IS NOT NULL", reportId) > 0
        val refunded = count(
            "SELECT COALESCE(SUM(quantity), 0) FROM coin_histories WHERE user_id = ? AND category = 'REPORT_PURCHASE_REFUND'",
            userId,
        )
        val price = if (isPaid) LIST_PRICE else 0
        val expectedRefund = if (isPaid && status in listOf("CANCELED", "FAILED")) LIST_PRICE.toLong() else 0L

        if (outcomes.any { it.endsWith(":deadlock") }) violations += "deadlock"
        if (outcomes.any { it.endsWith(":lockTimeout") }) violations += "lockTimeout"
        if (outcomes.any { it.contains(":error:") }) violations += "unexpectedError"
        if (refundCount > 1) violations += "doubleRefund"
        if (hasResult && (status != "COMPLETED" || refundCount > 0)) violations += "refundAfterDelivery"
        if (refunded != expectedRefund || balance(userId) != INITIAL_BALANCE - price + refunded.toInt()) {
            violations += "balanceMismatch"
        }
        // B가 다시 가져갔다면 그 뒤에 A가 쓴 결과는 늦게 끝난 Worker의 쓰기다(A가 먼저 끝냈다면 작업이 지워져 B가 가져가지 못한다).
        if (workerBLeased && workerAWrote) violations += "staleWrite"
        if (status !in listOf("COMPLETED", "CANCELED", "FAILED")) violations += "notEnded"
        if (count("SELECT COUNT(*) FROM report_generation_jobs WHERE report_id = ?", reportId) > 0) {
            violations += "leftoverJob"
            // 다음 라운드의 lease가 이 작업을 가져가지 않게 지운다.
            jdbcTemplate.update("DELETE FROM report_generation_jobs WHERE report_id = ?", reportId)
        }
        return violations
    }

    /** Worker가 가져갈 작업을 하나 lease한다. 앞 라운드의 작업은 남지 않으므로 이 라운드의 작업이다. */
    private fun lease(): ReportGenerationJobResult? =
        reportGenerationJobTransactionService.leaseReportGenerationJobs(1).leasedJobs.firstOrNull()

    /**
     * 라운드마다 새 사용자를 만든다. 유료 라운드는 무료 기회를 이미 쓴 사용자(완료된 무료 리포트가 있음)로 만든다.
     * 구매할 주에는 분석이 완료된 식사 3끼를 넣는다.
     */
    private fun createUser(isPaid: Boolean): Long {
        val userId = userRepository.save(User(AuthProvider.EMAIL, email = "${UUID.randomUUID()}@race.test")).id
        coinRepository.save(Coin(userId).also { it.balance = INITIAL_BALANCE })
        (1L..3L).forEach { day ->
            mealRepository.save(
                Meal(
                    status = MealStatus.COMPLETED,
                    imageKey = "meals/$userId/${UUID.randomUUID()}.jpg",
                    mealAt = SUNDAY.plusDays(day).atTime(3, 0).toInstant(ZoneOffset.UTC),
                    userId = userId,
                ),
            )
        }
        if (isPaid) {
            jdbcTemplate.update(
                """
                INSERT INTO reports (user_id, start_date, status, title, view_type, content, completed_at)
                VALUES (?, ?, 'COMPLETED', 't', 'NutritionBalance', '{}', NOW())
                """,
                userId,
                SUNDAY.minusWeeks(1),
            )
            // 호출마다 다른 연결일 수 있어 LAST_INSERT_ID() 대신 사용자로 찾는다.
            jdbcTemplate.update(
                """
                INSERT INTO report_purchases (user_id, report_id, price, idempotency_key)
                SELECT user_id, id, 0, ? FROM reports WHERE user_id = ?
                """,
                UUID.randomUUID().toString(),
                userId,
            )
        }
        return userId
    }

    // FK가 쓸 인덱스를 먼저 만든 뒤 유니크 제약을 지운다(FK의 인덱스를 겸하고 있어 그냥은 지울 수 없다).
    private fun dropRefundUnique() {
        jdbcTemplate.execute(
            "ALTER TABLE report_purchase_refunds ADD INDEX idx_report_purchase_refunds_report_purchase_id (report_purchase_id)",
        )
        jdbcTemplate.execute("ALTER TABLE report_purchase_refunds DROP INDEX uk_report_purchase_refunds_report_purchase_id")
    }

    private fun status(reportId: Long): String =
        jdbcTemplate.queryForObject("SELECT status FROM reports WHERE id = ?", String::class.java, reportId)!!

    private fun balance(userId: Long): Int =
        jdbcTemplate.queryForObject("SELECT balance FROM coins WHERE user_id = ?", Int::class.java, userId)!!

    private fun count(sql: String, vararg args: Any): Long = jdbcTemplate.queryForObject(sql, Long::class.java, *args)!!

    // 데드락이 났다면 InnoDB가 기억하는 마지막 데드락(어느 트랜잭션이 어떤 잠금을 쥐고 기다렸는지)을 남긴다.
    private fun printLatestDeadlock() {
        // PROCESS 권한이 필요해 테스트 컨테이너의 root로 따로 연결한다.
        val status = java.sql.DriverManager.getConnection(mysql.jdbcUrl, "root", mysql.password).use { connection ->
            connection.createStatement().executeQuery("SHOW ENGINE INNODB STATUS").use { result ->
                if (result.next()) result.getString("Status") else null
            }
        }
        val deadlock = status?.substringAfter("LATEST DETECTED DEADLOCK", "")?.substringBefore("TRANSACTIONS\n")
        if (!deadlock.isNullOrBlank()) {
            println("RACE_LATEST_DEADLOCK$deadlock")
        }
    }
}

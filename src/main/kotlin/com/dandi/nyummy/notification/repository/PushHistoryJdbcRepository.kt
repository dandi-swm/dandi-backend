package com.dandi.nyummy.notification.repository

import com.dandi.nyummy.notification.entity.PushHistory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.PreparedStatement
import java.sql.Timestamp

/**
 * 전송 내역을 JDBC 배치로 저장한다. 조회·삭제는 [PushHistoryRepository]가 한다.
 *
 * JPA `saveAll`을 쓰지 않는 이유: id가 IDENTITY라 Hibernate가 id를 알려고 행마다 INSERT를 바로 보내고,
 * `hibernate.jdbc.batch_size`를 켜도 IDENTITY 엔티티의 INSERT는 묶지 않는다.
 * 식사 리마인더는 한 묶음에 수백 행을 남겨 그만큼 DB 왕복이 생긴다.
 */
@Repository
class PushHistoryJdbcRepository(private val jdbcTemplate: JdbcTemplate) {
    companion object {
        private const val INSERT_SQL = """
            insert into push_histories (user_id, notification_id, category, platform, title, body, outcome, sent_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
        """
    }

    /**
     * 전송 내역을 한 번의 `executeBatch()`로 넣는다. 넘긴 엔티티의 id는 채우지 않는다.
     *
     * 트랜잭션으로 묶는다. 묶지 않으면 자동 커밋이라 문장마다 커밋하고, 중간에 실패하면 일부만 남는다.
     */
    @Transactional
    fun createPushHistories(pushHistories: List<PushHistory>) {
        if (pushHistories.isEmpty()) {
            return
        }

        // 전부 한 묶음으로 모아 executeBatch()를 한 번만 부른다
        val batchSize = pushHistories.size

        // 행 하나의 값을 INSERT_SQL의 ?에 순서대로(1부터) 채우는 방법.
        // JdbcTemplate이 행마다 이것을 부르고 addBatch()로 모았다가, batchSize개가 모이면 executeBatch()로 보낸다
        val rowSetter = ParameterizedPreparedStatementSetter<PushHistory> { statement, pushHistory ->
            setInsertValues(statement, pushHistory)
        }

        jdbcTemplate.batchUpdate(INSERT_SQL, pushHistories, batchSize, rowSetter)
    }

    private fun setInsertValues(statement: PreparedStatement, pushHistory: PushHistory) {
        statement.setLong(1, pushHistory.userId) // user_id
        statement.setString(2, pushHistory.notificationId) // notification_id
        statement.setString(3, pushHistory.category.name) // category
        statement.setString(4, pushHistory.platform?.name) // platform (기기 토큰을 못 찾으면 null)
        statement.setString(5, pushHistory.title) // title
        statement.setString(6, pushHistory.body) // body
        statement.setString(7, pushHistory.outcome.name) // outcome
        // sent_at. 드라이버가 connectionTimeZone(UTC) 기준 시각으로 보낸다. Hibernate가 Instant를 보내는 방식과 같다
        statement.setTimestamp(8, Timestamp.from(pushHistory.sentAt))
    }
}

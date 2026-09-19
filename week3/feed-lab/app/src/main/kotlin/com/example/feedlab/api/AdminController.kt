package com.example.feedlab.api

import com.example.feedlab.core.queryLongs
import com.example.feedlab.metrics.MetricsController
import com.example.feedlab.versions.FanoutWorker
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import kotlin.concurrent.withLock

/** 시연 보조: 리허설용 초기화, loadgen 이 쓸 유저 샘플. */
@RestController
class AdminController(
    @Qualifier("opsJdbc") private val jdbc: JdbcTemplate,
    private val worker: FanoutWorker,
    private val metrics: MetricsController,
) {
    /** 시드 이후에 생긴 글, 그 글의 feeds, 잡을 지운다. 시드는 그대로 둔다. */
    @PostMapping("/admin/reset")
    fun reset(): Map<String, Any> = worker.lock.withLock {
        val t0 = System.currentTimeMillis()
        val maxPostId = jdbc.queryLongs("SELECT max_post_id FROM seed_meta LIMIT 1").firstOrNull()
            ?: return mapOf("ok" to false, "reason" to "시드가 아직 없습니다 (make seed)")
        jdbc.update("DELETE FROM fanout_jobs")
        val feeds = jdbc.update("DELETE FROM feeds WHERE post_id > ?", maxPostId)
        val posts = jdbc.update("DELETE FROM posts WHERE id > ?", maxPostId)
        jdbc.execute("VACUUM feeds")
        metrics.resetBaseline()
        mapOf("ok" to true, "deletedPosts" to posts, "deletedFeeds" to feeds, "tookMs" to System.currentTimeMillis() - t0)
    }

    /** loadgen 이 기동할 때 가져가는 유저 표본. 페르소나는 부하 대상에서 뺀다. */
    @GetMapping("/internal/loadgen-users")
    fun loadgenUsers(): Map<String, Any> {
        fun readers(cohort: String) = jdbc.query(
            "SELECT id, follow_count FROM users WHERE cohort = ? ORDER BY random() LIMIT 5000",
            { rs, _ -> listOf(rs.getLong(1), rs.getLong(2)) }, cohort,
        )
        // 작성자: 절반은 아무나, 절반은 팔로워가 많은 축 (쓰기 산점도에 기울기가 보이도록). 셀럽은 버튼으로만 쓴다.
        val writers = jdbc.query(
            """
            (SELECT id, follower_count FROM users WHERE cohort <> 'persona' ORDER BY random() LIMIT 2000)
            UNION ALL
            (SELECT id, follower_count FROM users WHERE cohort <> 'persona' ORDER BY follower_count DESC LIMIT 2000)
            """,
        ) { rs, _ -> listOf(rs.getLong(1), rs.getLong(2)) }
        return mapOf(
            "readers" to mapOf("light" to readers("light"), "mid" to readers("mid"), "heavy" to readers("heavy")),
            "writers" to writers,
        )
    }
}

/** main 풀에서 3초 안에 커넥션을 못 얻으면 503. 포화 상태가 에러율로 드러난다. */
@RestControllerAdvice
class PoolTimeoutAdvice {
    @ExceptionHandler(CannotGetJdbcConnectionException::class, CannotCreateTransactionException::class, DataAccessResourceFailureException::class)
    fun poolTimeout(e: Exception): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to "db_pool_timeout", "message" to "DB 커넥션을 3초 안에 얻지 못했습니다"))
}

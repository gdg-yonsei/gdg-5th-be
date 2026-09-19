package com.example.feedlab.metrics

import com.example.feedlab.LabProps
import com.example.feedlab.LiveSettings
import com.example.feedlab.core.Fanout
import com.example.feedlab.versions.FanoutWorker
import com.example.feedlab.versions.V4Hybrid
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대시보드가 1초마다 읽는 가벼운 메트릭.
 * DB 를 치는 부분은 스케줄러(기본 250ms)가 ops 풀로 미리 계산해 두고, 엔드포인트는 메모리의 스냅샷만 돌려준다.
 * (v1 포화로 main 풀이 꽉 차 있어도 상황판은 멈추지 않는다)
 */
@RestController
class MetricsController(
    @Qualifier("opsJdbc") private val jdbc: JdbcTemplate,
    private val mainDataSource: HikariDataSource,
    private val timing: Timing,
    private val worker: FanoutWorker,
    private val v4: V4Hybrid,
    private val props: LabProps,
    private val live: LiveSettings,
) {
    @Volatile private var dbPart: Map<String, Any?> = emptyMap()
    @Volatile private var outboxPart: Map<String, Any?> = emptyMap()
    @Volatile private var feedsBaseline: Long = -1

    fun resetBaseline() { feedsBaseline = -1; Fanout.insertedRows.set(0); timing.clear() }

    @Scheduled(fixedRateString = "\${feedlab.metrics-interval-ms}")      // fixedDelay 작업들은 한 스레드에서 차례로 돈다. 워커가 20초짜리 잡을 돌리는 동안에도 메트릭은 갱신돼야 하므로 fixedRate
    fun collect() {
        if (feedsBaseline < 0) {
            // count(*) 는 너무 비싸다. 통계 테이블의 추정치를 기준선으로 쓰고, 이후 증가분은 앱이 직접 센다.
            feedsBaseline = jdbc.queryForObject("SELECT coalesce((SELECT feeds_rows FROM seed_meta LIMIT 1), (SELECT n_live_tup FROM pg_stat_user_tables WHERE relname = 'feeds'), 0)", Long::class.java) ?: 0
        }
        dbPart = mapOf(
            "activeConnections" to jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND datname = current_database()", Int::class.java),
            "feedsSizeMb" to jdbc.queryForObject("SELECT pg_total_relation_size('feeds') / 1048576", Long::class.java),
        )
        val pending = jdbc.queryForMap("SELECT count(*) AS n, coalesce(extract(epoch FROM clock_timestamp() - min(created_at)) * 1000, 0)::bigint AS oldest_ms FROM fanout_jobs WHERE status = 'pending'")
        val recent = jdbc.queryForList(
            """
            SELECT j.post_id AS "postId", p.author_id AS "authorId", j.status, j.strategy, j.fanout_count AS "fanoutCount",
                   (extract(epoch FROM j.finished_at - p.created_at) * 1000)::bigint AS "propagationMs"
            FROM fanout_jobs j JOIN posts p ON p.id = j.post_id
            WHERE j.finished_at > clock_timestamp() - interval '10 seconds'
            ORDER BY j.id DESC LIMIT 30
            """,
        )
        val delivered = recent.filter { it["status"] == "done" }.map { (it["propagationMs"] as Number).toLong() }
        outboxPart = mapOf(
            "pending" to (pending["n"] as Number).toInt(),
            "oldestPendingMs" to (pending["oldest_ms"] as Number).toLong(),
            "recentJobs" to recent,
            "avgPropagationMs" to if (delivered.isEmpty()) null else delivered.average().toLong(),
            "maxPropagationMs" to delivered.maxOrNull(),
        )
    }

    @GetMapping("/metrics-lite")
    fun metrics(): Map<String, Any?> {
        val pool = mainDataSource.hikariPoolMXBean
        val p = worker.processing
        return mapOf(
            "config" to mapOf(
                "hybridThreshold" to live.hybridThreshold, "workerIntervalMs" to props.workerIntervalMs,
                "fanoutBatchSize" to live.fanoutBatchSize, "celebCount" to v4.celebCount(),
            ),
            "db" to dbPart + mapOf(
                "feedsRows" to (if (feedsBaseline < 0) 0 else feedsBaseline + Fanout.insertedRows.get()),
                "feedsRowsAdded" to Fanout.insertedRows.get(),
                "poolActive" to (pool?.activeConnections ?: 0),
                "poolWaiting" to (pool?.threadsAwaitingConnection ?: 0),
                "poolMax" to mainDataSource.maximumPoolSize,
            ),
            "outbox" to outboxPart + mapOf(
                "processing" to p?.let { mapOf("postId" to it.postId, "authorId" to it.authorId, "delivered" to it.delivered, "total" to it.total) },
            ),
            "serverTiming" to timing.snapshot(),
            "serverNowMs" to System.currentTimeMillis(),
        )
    }
}

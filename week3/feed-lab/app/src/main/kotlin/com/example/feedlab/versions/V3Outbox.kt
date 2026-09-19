package com.example.feedlab.versions

import com.example.feedlab.core.Cursor
import com.example.feedlab.core.Fanout
import com.example.feedlab.core.FeedVersion
import com.example.feedlab.core.POST_MAPPER
import com.example.feedlab.core.Post
import com.example.feedlab.core.PostStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * v3. 쓸 때 "할 일"만 남긴다 (아웃박스 + 워커, 비동기 팬아웃)
 *
 *   쓰기: posts 와 fanout_jobs 를 한 트랜잭션에 넣고 바로 응답.
 *         -> 셀럽이 써도 수 ms. 글은 저장됐는데 잡이 없거나, 잡은 있는데 글이 없는 일은 생기지 않는다.
 *   읽기: v2 와 같다.
 *   대신: 팔로워 피드에 뜨기까지 시간이 걸린다 (반영 지연).
 */
@Component
class V3Outbox(
    private val jdbc: JdbcTemplate,
    private val posts: PostStore,
    private val v2: V2SyncFanout,
) : FeedVersion {

    @Transactional
    override fun write(authorId: Long, content: String): Post =
        writeWithJob(authorId, content, strategy = "full")

    fun writeWithJob(authorId: Long, content: String, strategy: String): Post {
        val post = posts.insert(authorId, content)
        jdbc.update(                                // 글과 "할 일"이 같은 트랜잭션: 둘 다 남거나 둘 다 안 남는다
            "INSERT INTO fanout_jobs (post_id, strategy, status) VALUES (?, ?, 'pending')",
            post.id, strategy,
        )
        return post                                 // 팬아웃을 기다리지 않고 바로 응답
    }

    override fun read(me: Long, cursor: Cursor, limit: Int): List<Post> = v2.read(me, cursor, limit)
}

/**
 * 워커: 1초마다 깨어나 pending 잡을 "들어온 순서대로 하나씩" 처리한다.
 * fixedDelay 라서 앞 실행이 끝나야 다음 실행이 시작된다 (겹치지 않는다).
 * 한 줄로 처리하므로, 셀럽 잡이 앞에 있으면 뒤의 평범한 글도 같이 기다린다 (head-of-line blocking).
 */
@Component
class FanoutWorker(
    @Qualifier("opsJdbc") private val jdbc: JdbcTemplate,
    @Qualifier("workerFanout") private val fanout: Fanout,
    private val v4: V4Hybrid,
) {
    class Processing(val jobId: Long, val postId: Long, val authorId: Long) {
        @Volatile var delivered = 0
        @Volatile var total = 0
    }

    @Volatile var processing: Processing? = null
    val lock = ReentrantLock()          // 초기화(reset)와 겹치지 않게

    @Scheduled(fixedDelayString = "\${feedlab.worker-interval-ms}")
    fun drain() {
        while (true) {
            val done = lock.withLock { processNext() }
            if (!done) return
        }
    }

    private fun processNext(): Boolean {
        val job = jdbc.query(
            """
            SELECT j.id AS job_id, j.strategy, p.id, p.author_id, p.content, p.created_at
            FROM fanout_jobs j JOIN posts p ON p.id = j.post_id
            WHERE j.status = 'pending'
            ORDER BY j.id
            LIMIT 1
            """,
        ) { rs, i -> Triple(rs.getLong("job_id"), rs.getString("strategy"), POST_MAPPER.mapRow(rs, i)!!) }
            .firstOrNull() ?: return false
        val (jobId, strategy, post) = job

        if (strategy == "hybrid" && v4.isCeleb(post.authorId)) {      // v4: 셀럽 글은 뿌리지 않는다
            finish(jobId, "skipped", 0)
            return true
        }

        jdbc.update("UPDATE fanout_jobs SET started_at = clock_timestamp() WHERE id = ?", jobId)
        val state = Processing(jobId, post.id, post.authorId).also { processing = it }
        try {
            val count = fanout.deliver(post) { delivered, total ->
                state.delivered = delivered; state.total = total   // 상황판의 "처리 중 35,000/50,000"
            }
            finish(jobId, "done", count)
        } finally {
            processing = null
        }
        return true
    }

    private fun finish(jobId: Long, status: String, count: Int) {
        jdbc.update(
            "UPDATE fanout_jobs SET status = ?, finished_at = clock_timestamp(), fanout_count = ? WHERE id = ?",
            status, count, jobId,
        )
    }
}

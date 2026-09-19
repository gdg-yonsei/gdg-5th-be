package com.example.feedlab.versions

import com.example.feedlab.LiveSettings
import com.example.feedlab.core.Cursor
import com.example.feedlab.core.FeedVersion
import com.example.feedlab.core.POST_MAPPER
import com.example.feedlab.core.Post
import com.example.feedlab.core.PostStore
import com.example.feedlab.core.queryLongs
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * v4. 하이브리드: 보통 사람 글은 push, 셀럽 글은 pull
 *
 *   쓰기: v3 와 같다. 다만 워커가 "팔로워가 임계값을 넘는 작성자"의 잡은 건너뛴다.
 *         -> 셀럽 잡이 큐를 막지 않는다.
 *   읽기: 내 feeds 20줄 + 내가 팔로우한 셀럽들의 최근 글을 직접 가져와서 합친다.
 *         -> 읽기가 조금 비싸진다. 비용이 또 옮겨갔다.
 */
@Component
class V4Hybrid(
    private val jdbc: JdbcTemplate,
    @Qualifier("opsJdbc") private val opsJdbc: JdbcTemplate,
    private val v3: V3Outbox,
    private val v2: V2SyncFanout,
    private val live: LiveSettings,
) : FeedVersion {

    @Volatile private var celebIds: Set<Long> = emptySet()

    fun isCeleb(userId: Long): Boolean = userId in celebIds
    fun celebCount(): Int = celebIds.size

    @Scheduled(fixedRate = 3000)                    // 셀럽 명단은 몇 명 안 되니 메모리에 들고 있는다
    fun refreshCelebs() {
        celebIds = opsJdbc
            .queryLongs("SELECT id FROM users WHERE follower_count > ?", live.hybridThreshold)
            .toSet()
    }

    @Transactional
    override fun write(authorId: Long, content: String): Post =
        v3.writeWithJob(authorId, content, strategy = "hybrid")

    override fun read(me: Long, cursor: Cursor, limit: Int): List<Post> {
        val pushed = v2.read(me, cursor, limit)             // push 된 글 (feeds)
        val pulled = pullFromCelebs(me, cursor, limit)      // 셀럽 글은 읽을 때 가져온다
        return (pushed + pulled)
            .distinctBy { it.id }                           // v3 시절에 이미 뿌려진 셀럽 글과 겹칠 수 있다
            .sortedWith(PostStore.NEWEST_FIRST)
            .take(limit)
    }

    private fun pullFromCelebs(me: Long, cursor: Cursor, limit: Int): List<Post> {
        if (celebIds.isEmpty()) return emptyList()
        return jdbc.query(
            """
            SELECT p.id, p.author_id, p.content, p.created_at
            FROM follows f
            JOIN LATERAL (
                SELECT id, author_id, content, created_at
                FROM posts
                WHERE author_id = f.followee_id
                  AND (created_at, id) < (?, ?)
                ORDER BY created_at DESC, id DESC
                LIMIT ?
            ) p ON true
            WHERE f.follower_id = ?
              AND f.followee_id = ANY(?)            -- v1 과 같은 쿼리지만 대상이 "내가 팔로우한 셀럽"뿐
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT ?
            """,
            POST_MAPPER, cursor.ts, cursor.id, limit, me, celebIds.toTypedArray(), limit,
        )
    }
}

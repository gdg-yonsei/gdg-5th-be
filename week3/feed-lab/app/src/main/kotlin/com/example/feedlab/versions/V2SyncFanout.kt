package com.example.feedlab.versions

import com.example.feedlab.core.Cursor
import com.example.feedlab.core.Fanout
import com.example.feedlab.core.FeedVersion
import com.example.feedlab.core.Post
import com.example.feedlab.core.PostStore
import com.example.feedlab.core.queryLongs
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * v2. 쓸 때 뿌린다 (fan-out on write, 동기)
 *
 *   쓰기: posts 에 넣고, 같은 요청 안에서 팔로워 전원의 feeds 에 꽂는다.
 *         -> 비용이 "나를 팔로우한 사람 수"에 비례한다. 셀럽이 쓰면 요청이 몇 초씩 걸린다.
 *   읽기: 내 feeds 에서 20줄. 팔로우를 몇 명 하든 똑같이 빠르다.
 */
@Component
class V2SyncFanout(
    private val jdbc: JdbcTemplate,
    private val posts: PostStore,
    private val fanout: Fanout,
) : FeedVersion {

    @Transactional
    override fun write(authorId: Long, content: String): Post {
        val post = posts.insert(authorId, content)
        fanout.deliver(post)                        // 응답을 보내기 전에 끝까지 기다린다
        return post
    }

    override fun read(me: Long, cursor: Cursor, limit: Int): List<Post> {
        val ids = jdbc.queryLongs(
            """
            SELECT post_id FROM feeds
            WHERE user_id = ? AND (created_at, post_id) < (?, ?)
            ORDER BY created_at DESC, post_id DESC
            LIMIT ?
            """,
            me, cursor.ts, cursor.id, limit,
        )
        return posts.findByIds(ids)
    }
}

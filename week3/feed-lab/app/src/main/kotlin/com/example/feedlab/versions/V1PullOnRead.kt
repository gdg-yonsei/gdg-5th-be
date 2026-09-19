package com.example.feedlab.versions

import com.example.feedlab.core.Cursor
import com.example.feedlab.core.FeedVersion
import com.example.feedlab.core.POST_MAPPER
import com.example.feedlab.core.Post
import com.example.feedlab.core.PostStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * v1. 읽을 때 모은다 (fan-out on read)
 *
 *   쓰기: posts 에 한 줄. 끝.
 *   읽기: 내가 팔로우한 사람마다 최근 글 20개씩 가져와서 합친 뒤 최신 20개.
 *         -> 비용이 "내가 팔로우한 사람 수"에 비례한다.
 */
@Component
class V1PullOnRead(private val jdbc: JdbcTemplate, private val posts: PostStore) : FeedVersion {

    override fun write(authorId: Long, content: String): Post =
        posts.insert(authorId, content)

    override fun read(me: Long, cursor: Cursor, limit: Int): List<Post> =
        jdbc.query(
            """
            SELECT p.id, p.author_id, p.content, p.created_at
            FROM follows f
            JOIN LATERAL (                          -- 팔로우한 사람 한 명당 한 번씩 실행된다
                SELECT id, author_id, content, created_at
                FROM posts WHERE author_id = f.followee_id
                  AND (created_at, id) < (?, ?)
                ORDER BY created_at DESC, id DESC
                LIMIT ?
            ) p ON true
            WHERE f.follower_id = ?
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT ?
            """,
            POST_MAPPER, cursor.ts, cursor.id, limit, me, limit,
        )
}

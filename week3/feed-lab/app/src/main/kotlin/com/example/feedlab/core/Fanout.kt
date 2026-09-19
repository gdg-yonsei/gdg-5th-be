package com.example.feedlab.core

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.util.concurrent.atomic.AtomicLong

/**
 * 팬아웃: 글 하나를 팔로워 전원의 feeds 에 꽂는다.
 * v2 는 요청 스레드에서, v3/v4 는 워커에서 같은 코드를 부른다. 비용은 팔로워 수에 비례한다.
 */
class Fanout(private val jdbc: JdbcTemplate, private val batchSize: () -> Int) {

    fun deliver(post: Post, onProgress: (delivered: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val followers = jdbc.queryLongs("SELECT follower_id FROM follows WHERE followee_id = ?", post.authorId)
        val createdAt = Timestamp.from(post.createdAt)
        var delivered = 0
        for (chunk in followers.chunked(batchSize())) {
            jdbc.batchUpdate(
                // PK(user_id, post_id) + DO NOTHING: 같은 잡을 다시 돌려도 중복이 생기지 않는다
                "INSERT INTO feeds (user_id, post_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                chunk.map { arrayOf<Any>(it, post.id, createdAt) },
            )
            delivered += chunk.size
            insertedRows.addAndGet(chunk.size.toLong())
            onProgress(delivered, followers.size)
        }
        return delivered
    }

    companion object {
        /** 대시보드의 "feeds 행 수" 카드가 실시간으로 올라가도록 앱에서 직접 센다. */
        val insertedRows = AtomicLong(0)
    }
}

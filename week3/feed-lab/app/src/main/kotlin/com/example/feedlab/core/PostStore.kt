package com.example.feedlab.core

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PostStore(private val jdbc: JdbcTemplate) {

    fun insert(authorId: Long, content: String): Post =
        jdbc.queryForObject(
            """
            INSERT INTO posts (author_id, content, created_at)
            VALUES (?, ?, clock_timestamp())
            RETURNING id, author_id, content, created_at
            """,
            POST_MAPPER, authorId, content,
        )!!

    /** feeds 에서 얻은 post_id 20개를 한 번에 가져온다. */
    fun findByIds(ids: List<Long>): List<Post> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.query(
            "SELECT id, author_id, content, created_at FROM posts WHERE id = ANY(?)",
            POST_MAPPER, ids.toTypedArray(),
        ).sortedWith(NEWEST_FIRST)
    }

    companion object {
        val NEWEST_FIRST: Comparator<Post> = compareByDescending<Post> { it.createdAt }.thenByDescending { it.id }
    }
}

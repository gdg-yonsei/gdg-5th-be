package com.example.feedlab.core

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

data class Post(val id: Long, val authorId: Long, val content: String, val createdAt: Instant)

val POST_MAPPER = RowMapper { rs, _ ->
    Post(rs.getLong("id"), rs.getLong("author_id"), rs.getString("content"), rs.getTimestamp("created_at").toInstant())
}

/** 모든 버전이 같은 커서를 쓴다: "이 글보다 오래된 것부터". (created_at, id) 를 마이크로초 단위로 base64. */
data class Cursor(val createdAt: Instant, val id: Long) {
    val ts: Timestamp get() = Timestamp.from(createdAt)

    fun encode(): String {
        val micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt)
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$micros,$id".toByteArray())
    }

    companion object {
        val START = Cursor(Instant.parse("9999-01-01T00:00:00Z"), Long.MAX_VALUE)

        fun decode(s: String?): Cursor {
            if (s.isNullOrBlank()) return START
            val (micros, id) = String(Base64.getUrlDecoder().decode(s)).split(",")
            return Cursor(Instant.EPOCH.plus(micros.toLong(), ChronoUnit.MICROS), id.toLong())
        }

        fun after(post: Post) = Cursor(post.createdAt, post.id)
    }
}

/** 버전마다 "쓰기"와 "읽기"를 어떻게 하는지만 다르다. */
interface FeedVersion {
    fun write(authorId: Long, content: String): Post
    fun read(me: Long, cursor: Cursor, limit: Int = 20): List<Post>
}

/** id 목록처럼 BIGINT 한 컬럼만 돌려주는 쿼리용. */
fun JdbcTemplate.queryLongs(sql: String, vararg args: Any): List<Long> =
    query(sql, { rs, _ -> rs.getLong(1) }, *args)

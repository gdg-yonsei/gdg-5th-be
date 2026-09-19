package com.example.feedlab.api

import com.example.feedlab.core.Cursor
import com.example.feedlab.core.FeedVersion
import com.example.feedlab.core.Post
import com.example.feedlab.versions.V1PullOnRead
import com.example.feedlab.versions.V2SyncFanout
import com.example.feedlab.versions.V3Outbox
import com.example.feedlab.versions.V4Hybrid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class PostDto(val id: Long, val authorId: Long, val content: String, val createdAtMs: Long) {
    constructor(p: Post) : this(p.id, p.authorId, p.content, p.createdAt.toEpochMilli())
}
data class FeedResponse(val items: List<PostDto>, val nextCursor: String?, val serverNowMs: Long, val serverMs: Double)
data class WriteRequest(val content: String = "")
data class WriteResponse(val post: PostDto, val serverNowMs: Long, val serverMs: Double)

/** 같은 기능을 버전별 경로로 노출한다: /v1/feed, /v2/feed, ... 화면에서 버전만 바꿔가며 비교한다. */
@RestController
class FeedController(v1: V1PullOnRead, v2: V2SyncFanout, v3: V3Outbox, v4: V4Hybrid) {

    private val versions: Map<String, FeedVersion> = mapOf("v1" to v1, "v2" to v2, "v3" to v3, "v4" to v4)

    private fun version(v: String) = versions[v] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "unknown version $v")

    @GetMapping("/{v:v[1-4]}/feed")
    fun feed(
        @PathVariable v: String,
        @RequestHeader("X-User-Id") me: Long,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<FeedResponse> {
        val t0 = System.nanoTime()
        val posts = version(v).read(me, Cursor.decode(cursor))
        val ms = (System.nanoTime() - t0) / 1e6
        val next = if (posts.size == 20) Cursor.after(posts.last()).encode() else null
        return ResponseEntity.ok()
            .header("X-Server-Time-Ms", "%.1f".format(ms))
            .body(FeedResponse(posts.map(::PostDto), next, System.currentTimeMillis(), ms))
    }

    @PostMapping("/{v:v[1-4]}/posts")
    fun write(
        @PathVariable v: String,
        @RequestHeader("X-User-Id") me: Long,
        @RequestBody body: WriteRequest,
    ): ResponseEntity<WriteResponse> {
        val t0 = System.nanoTime()
        val post = version(v).write(me, body.content.ifBlank { "(내용 없음)" }.take(500))
        val ms = (System.nanoTime() - t0) / 1e6
        return ResponseEntity.ok()
            .header("X-Server-Time-Ms", "%.1f".format(ms))
            .body(WriteResponse(PostDto(post), System.currentTimeMillis(), ms))
    }
}

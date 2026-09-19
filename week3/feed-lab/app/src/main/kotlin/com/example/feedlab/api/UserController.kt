package com.example.feedlab.api

import com.example.feedlab.LabProps
import com.example.feedlab.LiveSettings
import com.example.feedlab.versions.V4Hybrid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class UserDto(val id: Long, val name: String, val cohort: String, val followCount: Int, val followerCount: Int)

/** 버전과 무관한 기능: 유저 조회, 팔로우/언팔로우, 설정. 부하에 굶지 않게 ops 풀을 쓴다. */
@RestController
class UserController(
    @Qualifier("opsJdbc") private val jdbc: JdbcTemplate,
    private val props: LabProps,
    private val live: LiveSettings,
    private val v4: V4Hybrid,
) {
    private val mapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
        UserDto(rs.getLong("id"), rs.getString("name"), rs.getString("cohort"), rs.getInt("follow_count"), rs.getInt("follower_count"))
    }

    @GetMapping("/users/{id}")
    fun user(@PathVariable id: Long): UserDto =
        jdbc.query("SELECT * FROM users WHERE id = ?", mapper, id).firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @GetMapping("/personas")
    fun personas(): List<UserDto> = jdbc.query("SELECT * FROM users WHERE cohort = 'persona' ORDER BY id", mapper)

    @PostMapping("/follows/{target}")
    fun follow(@RequestHeader("X-User-Id") me: Long, @PathVariable target: Long): Map<String, Any> {
        val added = jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT DO NOTHING", me, target)
        if (added == 1) {
            jdbc.update("UPDATE users SET follow_count = follow_count + 1 WHERE id = ?", me)
            jdbc.update("UPDATE users SET follower_count = follower_count + 1 WHERE id = ?", target)
        }
        return mapOf("following" to true, "changed" to (added == 1))
    }

    @DeleteMapping("/follows/{target}")
    fun unfollow(@RequestHeader("X-User-Id") me: Long, @PathVariable target: Long): Map<String, Any> {
        val removed = jdbc.update("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?", me, target)
        if (removed == 1) {
            jdbc.update("UPDATE users SET follow_count = follow_count - 1 WHERE id = ?", me)
            jdbc.update("UPDATE users SET follower_count = follower_count - 1 WHERE id = ?", target)
        }
        return mapOf("following" to false, "changed" to (removed == 1))
    }

    /** 시연 중 "배치로 묶으면?", "임계값을 바꾸면?" 질문에 바로 답하기 위한 설정 변경. */
    @PostMapping("/config")
    fun update(@RequestBody body: Map<String, Int>): Map<String, Any> {
        body["fanoutBatchSize"]?.let { live.fanoutBatchSize = it.coerceIn(1, 10_000) }
        body["hybridThreshold"]?.let { live.hybridThreshold = it.coerceAtLeast(1); v4.refreshCelebs() }
        return config()
    }

    @GetMapping("/config")
    fun config(): Map<String, Any> = mapOf(
        "hybridThreshold" to live.hybridThreshold,
        "fanoutBatchSize" to live.fanoutBatchSize,
        "workerIntervalMs" to props.workerIntervalMs,
        "loadgenPort" to props.loadgenPort,
        "celebCount" to v4.celebCount(),
    )
}

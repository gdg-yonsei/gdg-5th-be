package com.example.feedlab.metrics

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/** 엔드포인트별 서버 처리 시간을 최근 10초만 들고 있는다 (보조 지표. 메인은 loadgen 측정). */
@Component
class Timing : OncePerRequestFilter() {
    private data class Sample(val atMs: Long, val ms: Double, val ok: Boolean)
    private val samples = ConcurrentHashMap<String, ConcurrentLinkedDeque<Sample>>()
    private val tracked = Regex("^/v[1-4]/(feed|posts)$")

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        if (!tracked.matches(req.requestURI)) return chain.doFilter(req, res)
        val t0 = System.nanoTime()
        try {
            chain.doFilter(req, res)
        } finally {
            val key = "${req.method} ${req.requestURI}"
            samples.computeIfAbsent(key) { ConcurrentLinkedDeque() }
                .add(Sample(System.currentTimeMillis(), (System.nanoTime() - t0) / 1e6, res.status < 400))
        }
    }

    fun snapshot(windowMs: Long = 10_000): Map<String, Map<String, Any>> {
        val cutoff = System.currentTimeMillis() - windowMs
        val out = sortedMapOf<String, Map<String, Any>>()
        for ((key, q) in samples) {
            while (true) { val h = q.peekFirst() ?: break; if (h.atMs < cutoff) q.pollFirst() else break }
            val ok = q.filter { it.ok }.map { it.ms }.sorted()
            if (q.isEmpty()) continue
            out[key] = mapOf(
                "count" to q.size,
                "errors" to q.count { !it.ok },
                "avgMs" to (if (ok.isEmpty()) 0.0 else ok.average()).round1(),
                "p50Ms" to ok.pct(0.50).round1(),
                "p99Ms" to ok.pct(0.99).round1(),
                "maxMs" to (ok.lastOrNull() ?: 0.0).round1(),
            )
        }
        return out
    }

    fun clear() = samples.clear()
}

fun List<Double>.pct(p: Double): Double = if (isEmpty()) 0.0 else this[((size - 1) * p).toInt()]
fun Double.round1(): Double = Math.round(this * 10) / 10.0

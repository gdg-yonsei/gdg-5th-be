package com.example.loadgen

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// ---- 설정 (프론트가 POST /control 로 보낸다. loadgen 은 프리셋을 모른다) ---------------------------------
data class ReadCfg(val targetRps: Double = 0.0, val mix: String = "uniform")     // uniform | heavy_only | light_only
data class WriteCfg(val targetRps: Double = 0.0)
data class Control(val version: String = "v1", val read: ReadCfg = ReadCfg(), val write: WriteCfg = WriteCfg())

class User(val id: Long, val x: Long)                                            // x = 팔로우 수(읽기) / 팔로워 수(쓰기)
class Sample(val atMs: Long, val gen: Int, val read: Boolean, val cohort: Int, val x: Long, val ms: Double, val ok: Boolean)

val COHORTS = listOf("light", "mid", "heavy")
const val WINDOW_MS = 5_000L
const val MAX_IN_FLIGHT = 5_000
const val REQUEST_TIMEOUT_SEC = 8L

val target: String = System.getenv("TARGET_URL") ?: "http://localhost:8080"
val mapper: ObjectMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()

@Volatile var control = Control()
@Volatile var generation = 0                       // 설정이 바뀔 때마다 +1. 통계는 현재 세대의 요청만 센다 (버전 전환 직후 숫자가 섞이지 않게)
@Volatile var changedAtMs = System.currentTimeMillis()
@Volatile var readers: List<List<User>> = listOf(emptyList(), emptyList(), emptyList())
@Volatile var writers: List<User> = emptyList()
val samples = ConcurrentLinkedDeque<Sample>()
val inFlight = AtomicInteger(0)
val writeSeq = AtomicLong(0)

fun main() {
    val scope = CoroutineScope(Dispatchers.Default)
    scope.launch { loadUsersUntilReady() }
    scope.launch { pace({ control.read.targetRps }) { fireRead() } }
    scope.launch { pace({ control.write.targetRps }) { fireWrite() } }

    embeddedServer(CIO, port = (System.getenv("PORT") ?: "8081").toInt()) {
        install(ContentNegotiation) { jackson() }
        install(CORS) {                       // 화면은 앱(:8080)에서 뜨고 여기(:8081)를 직접 부른다
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Post)
        }
        routing {
            get("/health") { call.respond(mapOf("ok" to true, "usersLoaded" to usersLoaded())) }
            get("/control") { call.respond(control) }
            post("/control") {
                val next = call.receive<Control>()
                if (next != control) { control = next; generation++; changedAtMs = System.currentTimeMillis() }
                call.respond(control)
            }
            post("/reload") { loadUsers(); call.respond(mapOf("usersLoaded" to usersLoaded())) }
            get("/stats") { call.respond(stats()) }
        }
    }.start(wait = true)
}

// ---- open-loop 발사기 --------------------------------------------------------------------------------
// 응답을 기다렸다가 다음 요청을 보내면(closed-loop) 서버가 느려질수록 부하도 같이 줄어서 천장이 안 보인다.
// 목표 RPS 만큼 시간에 맞춰 그냥 쏜다. 밀리면 밀리는 대로 지연과 에러로 드러난다.
suspend fun pace(rate: () -> Double, fire: () -> Unit) {
    var last = System.nanoTime()
    var credit = 0.0
    while (true) {
        delay(2)
        val now = System.nanoTime()
        val r = rate()
        credit = if (r <= 0) 0.0 else min(credit + (now - last) / 1e9 * r, max(r * 0.1, 1.0))
        last = now
        while (credit >= 1.0) { credit -= 1.0; fire() }
    }
}

fun fireRead() {
    val rnd = ThreadLocalRandom.current()
    val cohort = when (control.read.mix) { "heavy_only" -> 2; "light_only" -> 0; else -> rnd.nextInt(3) }
    val pool = readers[cohort]
    if (pool.isEmpty()) return
    val user = pool[rnd.nextInt(pool.size)]
    val req = HttpRequest.newBuilder(URI("$target/${control.version}/feed"))
        .header("X-User-Id", user.id.toString()).timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC)).GET().build()
    send(req, read = true, cohort = cohort, x = user.x)
}

fun fireWrite() {
    val pool = writers
    if (pool.isEmpty()) return
    val user = pool[ThreadLocalRandom.current().nextInt(pool.size)]
    val body = mapper.writeValueAsString(mapOf("content" to "부하 생성기가 쓴 글 #${writeSeq.incrementAndGet()}"))
    val req = HttpRequest.newBuilder(URI("$target/${control.version}/posts"))
        .header("X-User-Id", user.id.toString()).header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC)).POST(HttpRequest.BodyPublishers.ofString(body)).build()
    send(req, read = false, cohort = -1, x = user.x)
}

fun send(req: HttpRequest, read: Boolean, cohort: Int, x: Long) {
    val gen = generation
    if (inFlight.get() >= MAX_IN_FLIGHT) {                      // 더 못 쏜다: 버리고 에러로 센다
        samples.add(Sample(System.currentTimeMillis(), gen, read, cohort, x, 0.0, ok = false))
        return
    }
    inFlight.incrementAndGet()
    val t0 = System.nanoTime()
    http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete { res, err ->
        inFlight.decrementAndGet()
        val ok = err == null && res.statusCode() in 200..299
        samples.add(Sample(System.currentTimeMillis(), gen, read, cohort, x, (System.nanoTime() - t0) / 1e6, ok))
    }
}

// ---- 통계 (최근 5초) ----------------------------------------------------------------------------------
@Volatile var cachedStats: Pair<Long, Map<String, Any?>>? = null

/** 수만 rps 에서는 표본이 수십만 개라 매 요청마다 정렬하면 부하기가 자기 통계 내느라 바쁘다. 0.2초 동안은 같은 결과를 돌려준다. */
fun stats(): Map<String, Any?> {
    val now = System.currentTimeMillis()
    cachedStats?.let { (at, value) -> if (now - at < 200) return value }
    return computeStats(now).also { cachedStats = now to it }
}

fun computeStats(now: Long): Map<String, Any?> {
    while (true) { val h = samples.peekFirst() ?: break; if (h.atMs < now - WINDOW_MS - 1000) samples.pollFirst() else break }
    val from = max(now - WINDOW_MS, changedAtMs)
    val win = samples.filter { it.gen == generation && it.atMs >= from }
    val reads = win.filter { it.read }
    val writes = win.filter { !it.read }
    val sec = max(1.0, (now - from) / 1000.0)

    fun lat(list: List<Sample>): Map<String, Any?> {
        val ok = list.filter { it.ok }.map { it.ms }.sorted()
        return mapOf(
            "count" to list.size,
            "achievedRps" to r1(ok.size / sec),
            "sentRps" to r1(list.size / sec),
            "errorRate" to if (list.isEmpty()) 0.0 else Math.round((list.size - ok.size) * 1000.0 / list.size) / 1000.0,
            "avgMs" to r1(if (ok.isEmpty()) 0.0 else ok.average()),
            "p50Ms" to r1(pct(ok, 0.50)), "p99Ms" to r1(pct(ok, 0.99)), "maxMs" to r1(ok.lastOrNull() ?: 0.0),
        )
    }
    fun thin(list: List<Sample>, cap: Int): List<Sample> {
        val stride = max(1, ceil(list.size / cap.toDouble()).toInt())
        return list.filterIndexed { i, _ -> i % stride == 0 }
    }

    return mapOf(
        "windowSec" to sec, "version" to control.version, "inFlight" to inFlight.get(), "usersLoaded" to usersLoaded(),
        "read" to lat(reads) + mapOf(
            "targetRps" to control.read.targetRps, "mix" to control.read.mix,
            "byCohort" to COHORTS.indices.associate { c -> COHORTS[c] to lat(reads.filter { it.cohort == c }) },
        ),
        "write" to lat(writes) + mapOf("targetRps" to control.write.targetRps),
        // 산점도: [x, 지연(ms), 성공 여부, 코호트]
        "scatterRead" to thin(reads.filter { it.ms > 0 }, 400).map { listOf(it.x, r1(it.ms), if (it.ok) 1 else 0, it.cohort) },
        "scatterWrite" to thin(writes.filter { it.ms > 0 }, 300).map { listOf(it.x, r1(it.ms), if (it.ok) 1 else 0) },
    )
}

fun pct(sorted: List<Double>, p: Double): Double = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]
fun r1(v: Double): Double = Math.round(v * 10) / 10.0

// ---- 유저 표본: 앱이 시드된 뒤에야 의미가 있으므로 될 때까지 다시 가져온다 -----------------------------------
fun usersLoaded(): Int = readers.sumOf { it.size } + writers.size

suspend fun loadUsersUntilReady() {
    while (true) {
        runCatching { loadUsers() }.onFailure { System.err.println("유저 표본 로드 실패: ${it.message}") }
        if (readers.all { it.isNotEmpty() } && writers.isNotEmpty()) { println("유저 표본 로드 완료: ${usersLoaded()}명"); return }
        delay(3000)
    }
}

fun loadUsers() {
    val res = http.send(HttpRequest.newBuilder(URI("$target/internal/loadgen-users")).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString())
    val body: Map<String, Any> = mapper.readValue(res.body())
    @Suppress("UNCHECKED_CAST") val r = body["readers"] as Map<String, List<List<Number>>>
    @Suppress("UNCHECKED_CAST") val w = body["writers"] as List<List<Number>>
    readers = COHORTS.map { c -> r[c].orEmpty().map { User(it[0].toLong(), it[1].toLong()) } }
    writers = w.map { User(it[0].toLong(), it[1].toLong()) }
}

package com.example.spring

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals

class CounterTest {

    @Test
    fun `여러 개의 스레드로 100만 까지 세는 카운터가 올바르게 작동한다`() {
        var counter = 0

        val threadCount = 100
        val incrementsPerThread = 10_000

        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                start.await()

                repeat(incrementsPerThread) {
                    counter++
                }

                done.countDown()
            }.start()
        }

        start.countDown()
        done.await()

        val expected = threadCount * incrementsPerThread

        println("Expected: $expected")
        println("Actual:   $counter")

        assertEquals(expected,counter)
    }
}
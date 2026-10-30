package com.ecommerce.catalogservice.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

class LogThrottleTest {

    private val nanos = AtomicLong()
    private val throttle = LogThrottle(10, TimeUnit.SECONDS, LongSupplier { nanos.get() })

    private fun advanceSeconds(seconds: Long) {
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds))
    }

    @Test
    fun `first occurrence logs, with nothing suppressed`() {
        assertEquals(0, throttle.acquire("db-down"))
    }

    @Test
    fun `repeats inside the window are suppressed and counted`() {
        assertEquals(0, throttle.acquire("db-down"))

        repeat(5) {
            advanceSeconds(1)
            assertEquals(-1, throttle.acquire("db-down"))
        }

        advanceSeconds(10)
        assertEquals(5, throttle.acquire("db-down"), "next window reports the five suppressed lines")
        assertEquals(-1, throttle.acquire("db-down"), "and starts counting again")
        advanceSeconds(10)
        assertEquals(1, throttle.acquire("db-down"))
    }

    @Test
    fun `the window opens at the logged occurrence, not at the last suppressed one`() {
        throttle.acquire("k")
        advanceSeconds(9)
        assertEquals(-1, throttle.acquire("k"))
        advanceSeconds(1) // 10 s after the logged line, only 1 s after the last suppressed one
        assertEquals(1, throttle.acquire("k"))
    }

    @Test
    fun `keys are independent`() {
        assertEquals(0, throttle.acquire("a"))
        assertEquals(0, throttle.acquire("b"))
        assertEquals(-1, throttle.acquire("a"))
        assertEquals(-1, throttle.acquire("b"))
    }

    @Test
    fun `tracked keys are bounded at MAX_KEYS, the rest share one bucket`() {
        for (i in 0 until LogThrottle.MAX_KEYS) {
            assertEquals(0, throttle.acquire("key-$i"), "key $i is tracked on its own")
        }

        // Past the limit every new key lands in the overflow bucket: one line per window for all of them.
        assertEquals(0, throttle.acquire("attacker-1"))
        assertEquals(-1, throttle.acquire("attacker-2"))
        assertEquals(-1, throttle.acquire("attacker-3"))
        assertEquals(-1, throttle.acquire(LogThrottle.OVERFLOW_KEY))
        // keys that were tracked before the limit keep their own window
        assertEquals(-1, throttle.acquire("key-0"))

        advanceSeconds(10)
        assertEquals(3, throttle.acquire("attacker-4"))
    }

    @Test
    fun `concurrent callers in one window log exactly once`() {
        val threads = 8
        val perThread = 1000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val logged = (1..threads).map {
            pool.submit<Int> {
                start.await()
                (1..perThread).count { throttle.acquire("hot") >= 0 }
            }
        }
        start.countDown()
        val total = logged.sumOf { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, total)
        advanceSeconds(10)
        assertEquals((threads * perThread - 1).toLong(), throttle.acquire("hot"))
    }

    @Test
    fun `suppressedSuffix is empty unless something was suppressed`() {
        assertEquals("", LogThrottle.suppressedSuffix(0))
        assertEquals("", LogThrottle.suppressedSuffix(-1))
        assertEquals(" (7 similar suppressed)", LogThrottle.suppressedSuffix(7))
    }

    @Test
    fun `keyOf picks the first frame in our own code`() {
        val t = IllegalStateException("boom")
        t.stackTrace = arrayOf(
            StackTraceElement("org.springframework.dao.Foo", "bar", "Foo.java", 10),
            StackTraceElement("com.ecommerce.catalogservice.service.CatalogService", "syncFromProductService", "CatalogService.kt", 42),
            StackTraceElement("com.ecommerce.catalogservice.controller.CatalogController", "sync", "CatalogController.kt", 7)
        )

        assertEquals("java.lang.IllegalStateException@com.ecommerce.catalogservice.service.CatalogService:42", LogThrottle.keyOf(t))
    }

    @Test
    fun `keyOf falls back to the top frame, or just the class`() {
        val foreignOnly = IllegalStateException("boom")
        foreignOnly.stackTrace = arrayOf(StackTraceElement("org.mongodb.Driver", "connect", "Driver.java", 99))
        val noFrames = IllegalStateException("boom")
        noFrames.stackTrace = emptyArray()

        assertEquals("java.lang.IllegalStateException@org.mongodb.Driver:99", LogThrottle.keyOf(foreignOnly))
        assertEquals("java.lang.IllegalStateException", LogThrottle.keyOf(noFrames))
    }

    @Test
    fun `the same failure site gives the same key, different sites do not`() {
        assertEquals(keyAt(10), keyAt(10))
        assertNotEquals(keyAt(10), keyAt(11))
    }

    private fun keyAt(line: Int): String {
        val t = RuntimeException()
        t.stackTrace = arrayOf(StackTraceElement("com.ecommerce.catalogservice.service.CatalogService", "m", "CatalogService.kt", line))
        return LogThrottle.keyOf(t)
    }
}

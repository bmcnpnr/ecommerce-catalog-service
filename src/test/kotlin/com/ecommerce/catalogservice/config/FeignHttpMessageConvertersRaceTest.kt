package com.ecommerce.catalogservice.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

/**
 * Hammers a *cold* converters instance from many threads released together,
 * the way the first burst of Feign calls after a pod start hits it.
 *
 * Spring Cloud OpenFeign 5.0.1's FeignHttpMessageConverters initialises its
 * list lazily without synchronisation — it publishes an empty ArrayList and
 * only then fills it — so a concurrent caller can observe an empty list and
 * SpringDecoder fails with "'messageConverters' must not be empty". The
 * thread-safe subclass registered by FeignConfig must never do that.
 */
class FeignHttpMessageConvertersRaceTest {

    private fun <T : Any> none(): ObjectProvider<T> = object : ObjectProvider<T> {
        override fun getObject(): T = throw IllegalStateException("none")
        override fun stream(): Stream<T> = Stream.empty()
        override fun orderedStream(): Stream<T> = Stream.empty()
    }

    /** Returns how many concurrent first calls observed an empty list. */
    private fun emptyObservations(converters: FeignHttpMessageConverters, threads: Int = 48, rounds: Int = 1): Int {
        val empty = AtomicInteger()
        repeat(rounds) {
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val start = CountDownLatch(1)
                val tasks = (1..threads).map {
                    Callable {
                        start.await()
                        if (converters.converters.isEmpty()) empty.incrementAndGet()
                    }
                }
                val futures = tasks.map { pool.submit(it) }
                start.countDown()
                futures.forEach { it.get() }
            } finally {
                pool.shutdownNow()
            }
        }
        return empty.get()
    }

    @Test
    fun `thread-safe converters never expose an empty list to a concurrent first caller`() {
        // Fresh (cold) instance per round, many rounds: the window is tiny, so
        // only repetition makes the test meaningful.
        var empties = 0
        repeat(200) {
            empties += emptyObservations(FeignConfig.ThreadSafeFeignHttpMessageConverters(none(), none()))
        }
        assertEquals(0, empties, "a concurrent caller saw an empty converter list")

        val warm = FeignConfig.ThreadSafeFeignHttpMessageConverters(none(), none())
        assertTrue(warm.converters.isNotEmpty())
        assertTrue(warm.converters === warm.converters, "initialised once, same list afterwards")
    }
}

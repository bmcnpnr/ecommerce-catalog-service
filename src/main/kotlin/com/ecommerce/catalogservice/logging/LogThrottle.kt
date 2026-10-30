package com.ecommerce.catalogservice.logging

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier

/**
 * Bounds how often one kind of log line is written. A failure that repeats at request, poll or
 * scheduler rate (a dead database under load, a bug on a hot path, a flood of bad tokens) logs its
 * first occurrence per window and counts the rest, so log volume grows with the number of distinct
 * problems instead of with traffic. See ecommerce-platform/docs/resilience-fix-spec.md (L7).
 *
 * Kotlin equivalent of the Java class every other service carries (ADR-001: no shared code); the
 * behaviour and the API ([acquire], [suppressedSuffix], [keyOf]) are identical.
 */
class LogThrottle internal constructor(window: Long, unit: TimeUnit, private val nanoClock: LongSupplier) {

    constructor(window: Long, unit: TimeUnit) : this(window, unit, LongSupplier { System.nanoTime() })

    private val windowNanos: Long = unit.toNanos(window)
    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * @return `-1` when this occurrence must not be logged; otherwise the number of occurrences
     *         suppressed since the last logged one (0 the first time)
     */
    fun acquire(key: String): Long {
        var window = windows[key]
        if (window == null) {
            val k = if (windows.size >= MAX_KEYS) OVERFLOW_KEY else key
            window = windows.computeIfAbsent(k) { Window() }
        }
        return window.acquire(nanoClock.asLong, windowNanos)
    }

    companion object {
        /** Distinct keys tracked; past this every new key shares one bucket, so the map stays bounded. */
        internal const val MAX_KEYS = 256
        internal const val OVERFLOW_KEY = "<other>"

        /** `" (N similar suppressed)"` for a positive count, otherwise `""`. */
        @JvmStatic
        fun suppressedSuffix(suppressed: Long): String = if (suppressed > 0) " ($suppressed similar suppressed)" else ""

        /** Exception class plus the first stack frame in our own code: one key per distinct failure site. */
        @JvmStatic
        fun keyOf(t: Throwable): String {
            val frames = t.stackTrace
            var site: StackTraceElement? = frames.firstOrNull()
            for (frame in frames) {
                if (frame.className.startsWith("com.ecommerce.")) {
                    site = frame
                    break
                }
            }
            return if (site == null) t.javaClass.name
            else t.javaClass.name + "@" + site.className + ":" + site.lineNumber
        }
    }

    private class Window {
        private var open = false
        private var openedAt = 0L
        private var suppressed = 0L

        @Synchronized
        fun acquire(now: Long, windowNanos: Long): Long {
            if (open && now - openedAt < windowNanos) {
                suppressed++
                return -1
            }
            open = true
            openedAt = now
            val count = suppressed
            suppressed = 0
            return count
        }
    }
}

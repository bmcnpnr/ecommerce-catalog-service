package com.ecommerce.catalogservice.config

import com.mongodb.MongoClientSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * D4: the driver's own defaults (30 s server selection, no socket timeouts, a 2 minute pool wait) make every request
 * hang through an outage. The settings the application builds must carry explicit, short ones.
 */
class MongoClientTimeoutsTest {

    private fun settingsFrom(
        serverSelection: Duration = Duration.ofSeconds(3),
        connect: Duration = Duration.ofSeconds(2),
        socket: Duration = Duration.ofSeconds(5),
        poolWait: Duration = Duration.ofSeconds(2)
    ): MongoClientSettings {
        val builder = MongoClientSettings.builder()
        MongoConfig().mongoClientTimeouts(serverSelection, connect, socket, poolWait).customize(builder)
        return builder.build()
    }

    @Test
    fun `the production defaults are short and explicit`() {
        val settings = settingsFrom()

        assertEquals(3000, settings.clusterSettings.getServerSelectionTimeout(TimeUnit.MILLISECONDS))
        assertEquals(2000, settings.socketSettings.getConnectTimeout(TimeUnit.MILLISECONDS))
        assertEquals(5000, settings.socketSettings.getReadTimeout(TimeUnit.MILLISECONDS))
        assertEquals(2000, settings.connectionPoolSettings.getMaxWaitTime(TimeUnit.MILLISECONDS))
    }

    @Test
    fun `none of them is the driver default any more`() {
        val driverDefaults = MongoClientSettings.builder().build()
        val ours = settingsFrom()

        assertEquals(30_000, driverDefaults.clusterSettings.getServerSelectionTimeout(TimeUnit.MILLISECONDS), "what we are replacing")
        assertEquals(0, driverDefaults.socketSettings.getReadTimeout(TimeUnit.MILLISECONDS), "the driver never times a read out by default")
        assertEquals(120_000, driverDefaults.connectionPoolSettings.getMaxWaitTime(TimeUnit.MILLISECONDS))
        org.junit.jupiter.api.Assertions.assertTrue(ours.clusterSettings.getServerSelectionTimeout(TimeUnit.MILLISECONDS) < 30_000)
        org.junit.jupiter.api.Assertions.assertTrue(ours.socketSettings.getReadTimeout(TimeUnit.MILLISECONDS) in 1..10_000)
        org.junit.jupiter.api.Assertions.assertTrue(ours.connectionPoolSettings.getMaxWaitTime(TimeUnit.MILLISECONDS) < 120_000)
    }

    @Test
    fun `they can be tuned`() {
        val settings = settingsFrom(serverSelection = Duration.ofMillis(500), socket = Duration.ofSeconds(15))

        assertEquals(500, settings.clusterSettings.getServerSelectionTimeout(TimeUnit.MILLISECONDS))
        assertEquals(15_000, settings.socketSettings.getReadTimeout(TimeUnit.MILLISECONDS))
    }
}

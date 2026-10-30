package com.ecommerce.catalogservice.exception

import com.mongodb.MongoCommandException
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.MongoSocketOpenException
import com.mongodb.MongoSocketReadTimeoutException
import com.mongodb.MongoTimeoutException
import com.mongodb.ServerAddress
import org.bson.BsonDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.mongodb.UncategorizedMongoDbException

class DependencyFailuresTest {

    private val address = ServerAddress("catalogdb", 27017)

    @Test
    fun `driver failures that mean the server did not answer are unavailable`() {
        assertNotNull(DependencyFailures.unavailableCause(MongoTimeoutException("Timed out while waiting for a server")))
        assertNotNull(DependencyFailures.unavailableCause(MongoSocketOpenException("connect failed", address, RuntimeException())))
        assertNotNull(DependencyFailures.unavailableCause(MongoSocketReadTimeoutException("Read timed out", address, RuntimeException())))
        assertNotNull(DependencyFailures.unavailableCause(MongoExecutionTimeoutException(50, "operation exceeded time limit")))
    }

    @Test
    fun `what Spring Data makes of them is unavailable too`() {
        assertNotNull(DependencyFailures.unavailableCause(DataAccessResourceFailureException("down", MongoTimeoutException("x"))))
        assertNotNull(DependencyFailures.unavailableCause(QueryTimeoutException("slow")))
    }

    @Test
    fun `the cause chain is walked - a wrapper does not hide a dead database`() {
        val wrapped = UncategorizedMongoDbException("wrapped", RuntimeException("outer", MongoTimeoutException("Timed out")))

        assertEquals(MongoTimeoutException::class.java, DependencyFailures.unavailableCause(wrapped)!!.javaClass)
    }

    @ParameterizedTest(name = "server error code {0} means unavailable")
    @ValueSource(ints = [50, 91, 11600, 11602, 10107, 13436])
    fun `command errors that mean the server is busy shutting down or failing over`(code: Int) {
        assertNotNull(DependencyFailures.unavailableCause(commandError(code)))
    }

    @ParameterizedTest(name = "server error code {0} is our own problem")
    @ValueSource(ints = [2, 13, 85, 86, 67, 11000])
    fun `command errors that are our own bug or a refusal stay errors`(code: Int) {
        assertNull(DependencyFailures.unavailableCause(commandError(code)))
    }

    @Test
    fun `write conflicts stay 409s, not 503s`() {
        assertNull(DependencyFailures.unavailableCause(DuplicateKeyException("E11000 duplicate key")))
        assertNull(DependencyFailures.unavailableCause(OptimisticLockingFailureException("version moved")))
    }

    @Test
    fun `ordinary bugs are not dependency failures`() {
        assertNull(DependencyFailures.unavailableCause(IllegalStateException("bug")))
        assertNull(DependencyFailures.unavailableCause(NullPointerException()))
    }

    @Test
    fun `a cause cycle does not loop forever`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertNull(DependencyFailures.unavailableCause(a))
    }

    @Test
    fun `the throwable that matched is returned`() {
        val timeout = MongoTimeoutException("Timed out")

        assertSame(timeout, DependencyFailures.unavailableCause(RuntimeException("outer", timeout)))
    }

    private fun commandError(code: Int) = MongoCommandException(BsonDocument.parse("""{"ok": 0, "code": $code, "errmsg": "x"}"""), address)
}

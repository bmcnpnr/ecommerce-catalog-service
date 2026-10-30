package com.ecommerce.catalogservice.exception

import com.mongodb.MongoCommandException
import com.mongodb.MongoConnectionPoolClearedException
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.MongoInterruptedException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoServerUnavailableException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException

/**
 * Tells "MongoDB did not answer" (no server selected within the timeout, socket errors, pool exhausted, a query
 * that hit its time limit, a server shutting down or failing over) apart from a bug. The first is an operating
 * condition: the handler answers 503 and logs one throttled line without a stack trace. The second keeps the 500
 * and the (throttled) stack trace.
 *
 * The whole cause chain is walked because Spring Data translates driver exceptions into its own and wraps them
 * again depending on where they surface. Write conflicts ([org.springframework.dao.DuplicateKeyException],
 * optimistic locking) are deliberately not in here: those stay 409.
 */
object DependencyFailures {

    private const val MAX_DEPTH = 16

    /** MaxTimeMSExpired, ShutdownInProgress, InterruptedAtShutdown, InterruptedDueToReplStateChange, NotWritablePrimary, NotPrimaryOrSecondary. */
    private val UNAVAILABLE_COMMAND_CODES = setOf(50, 91, 11600, 11602, 10107, 13436)

    /** @return the first throwable in the cause chain that means "dependency unavailable", or `null` */
    fun unavailableCause(failure: Throwable): Throwable? {
        var t: Throwable? = failure
        var depth = 0
        while (t != null && depth < MAX_DEPTH) {
            // a lost write race is a conflict (409) even though Spring files it under "transient"
            if (t is OptimisticLockingFailureException || t is DuplicateKeyException) return null
            if (isUnavailable(t)) return t
            val next = t.cause
            t = if (next === t) null else next
            depth++
        }
        return null
    }

    private fun isUnavailable(t: Throwable): Boolean = when (t) {
        is DataAccessResourceFailureException,      // what Spring Data makes of socket, timeout and selection errors
        is TransientDataAccessException,
        is RecoverableDataAccessException,
        is MongoTimeoutException,
        is MongoSocketException,
        is MongoServerUnavailableException,
        is MongoConnectionPoolClearedException,
        is MongoNotPrimaryException,
        is MongoNodeIsRecoveringException,
        is MongoExecutionTimeoutException,
        is MongoInterruptedException -> true
        is MongoCommandException -> t.errorCode in UNAVAILABLE_COMMAND_CODES
        else -> false
    }
}

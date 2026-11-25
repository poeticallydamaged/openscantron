package com.omrgrader.core

/** Explicit error hierarchy. No exception carries student names or IDs in its message. */
sealed class AppError(message: String, override val cause: Throwable? = null) : RuntimeException(message, cause) {
    class Validation(message: String) : AppError(message)
    class NotFound(message: String) : AppError(message)
    class Conflict(message: String) : AppError(message)
    class ImageQuality(message: String) : AppError(message)
    class Alignment(message: String) : AppError(message)
    class Recognition(message: String) : AppError(message)
    class Storage(message: String, cause: Throwable? = null) : AppError(message, cause)
    class Database(message: String, cause: Throwable? = null) : AppError(message, cause)
    class Unsupported(message: String) : AppError(message)
}

/** Framework-independent result wrapper. */
sealed class OmrResult<out T> {
    data class Success<out T>(val value: T) : OmrResult<T>()
    data class Failure(val error: AppError) : OmrResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    inline fun <R> map(transform: (T) -> R): OmrResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> OmrResult<R>): OmrResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    fun getOrNull(): T? = (this as? Success)?.value

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw error
    }

    companion object {
        inline fun <T> runCatching(block: () -> T): OmrResult<T> = try {
            Success(block())
        } catch (e: AppError) {
            Failure(e)
        } catch (e: IllegalArgumentException) {
            Failure(AppError.Validation(e.message ?: "invalid input"))
        } catch (e: java.io.IOException) {
            Failure(AppError.Storage(e.message ?: "I/O failure", e))
        } catch (e: Exception) {
            Failure(AppError.Storage(e::class.java.simpleName + ": " + (e.message ?: "unexpected failure"), e))
        }
    }
}

/** Suspend dispatcher boundary. Implemented by the Android layer with Dispatchers.IO/Default. */
interface AppDispatchers {
    suspend fun <T> onIo(block: suspend () -> T): T
    suspend fun <T> onDefault(block: suspend () -> T): T
    suspend fun <T> onMain(block: suspend () -> T): T
}

/** PII-safe logger: implementations must log identifiers/counts only, never names. */
interface AppLogger {
    fun info(event: String, vararg context: Pair<String, Any>)
    fun warn(event: String, vararg context: Pair<String, Any>)
    fun error(event: String, throwable: Throwable? = null, vararg context: Pair<String, Any>)
}

object NoopLogger : AppLogger {
    override fun info(event: String, vararg context: Pair<String, Any>) = Unit
    override fun warn(event: String, vararg context: Pair<String, Any>) = Unit
    override fun error(event: String, throwable: Throwable?, vararg context: Pair<String, Any>) = Unit
}

object Ids {
    fun newId(prefix: String): String = "$prefix-${java.util.UUID.randomUUID()}"
}

interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class FixedClock(initial: Long) : Clock {
    @Volatile
    private var now: Long = initial
    override fun nowMillis(): Long = now
    fun advance(millis: Long) { now += millis }
    fun set(millis: Long) { now = millis }
}

package com.omrgrader.platform

import android.util.Log
import com.omrgrader.core.AppDispatchers
import com.omrgrader.core.AppLogger
import com.omrgrader.core.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDispatchers @Inject constructor() : AppDispatchers {
    override suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    override suspend fun <T> onDefault(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }
    override suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }
}

/**
 * PII-safe logging. Context values whose key suggests a name are dropped; only IDs,
 * counts, statuses and file names are emitted. Student names/IDs never reach logcat.
 */
@Singleton
class AndroidAppLogger @Inject constructor() : AppLogger {
    private val sensitive = listOf("name", "pii", "fullname", "student_number")
    private fun clean(context: Array<out Pair<String, Any>>): String =
        context.filterNot { (key, _) -> sensitive.any { key.lowercase().contains(it) } }
            .joinToString(" ") { (k, v) -> "$k=$v" }

    override fun info(event: String, vararg context: Pair<String, Any>) {
        Log.i("OpenScantron", "$event ${clean(context)}")
    }
    override fun warn(event: String, vararg context: Pair<String, Any>) {
        Log.w("OpenScantron", "$event ${clean(context)}")
    }
    override fun error(event: String, throwable: Throwable?, vararg context: Pair<String, Any>) {
        Log.e("OpenScantron", "$event ${clean(context)} error=${throwable?.javaClass?.simpleName}")
    }
}

@Singleton
class AndroidClock @Inject constructor() : com.omrgrader.core.Clock by SystemClock

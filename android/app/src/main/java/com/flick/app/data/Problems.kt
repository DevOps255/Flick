package com.flick.app.data

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Collects the failures that would otherwise close the app without a word.
 *
 * An exception that nobody catches inside a background coroutine kills the whole app. Here it is
 * written to the log under the tag `Flick` and shown in a banner, so it can be read and reported.
 */
@Singleton
class Problems @Inject constructor() {
    private val _current = MutableStateFlow<String?>(null)

    /** The last failure, ready to show. Null when there is none. */
    val current: StateFlow<String?> = _current.asStateFlow()

    fun report(source: String, error: Throwable) {
        Log.e(TAG, "$source failed", error)
        _current.value = "$source: ${error::class.java.simpleName}: ${error.message ?: "no details"}"
    }

    fun dismiss() {
        _current.value = null
    }

    /** For coroutine scopes: reports an exception instead of letting it close the app. */
    fun handler(source: String) = CoroutineExceptionHandler { _, error -> report(source, error) }

    private companion object {
        const val TAG = "Flick"
    }
}

package com.flick.app.data

import android.content.Context
import android.os.Build
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.flick_ffi.FlickNode
import uniffi.flick_ffi.ReceiveObserver
import uniffi.flick_ffi.TransferEvent
import uniffi.flick_ffi.generateIdentity
import uniffi.flick_ffi.startNode

/**
 * Owns the Rust node: the HTTPS server that receives files and the client that sends them.
 *
 * The device identity is created once and kept in app private storage. Its
 * certificate fingerprint is what other phones pin, so it must not change.
 */
@Singleton
class NodeRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val lock = Mutex()
    private var node: FlickNode? = null
    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)

    /** Everything the receiving side reports. */
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    /** Where received files are written. Opened through a FileProvider, see `file_paths.xml`. */
    val inbox: File
        get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "Flick")

    /** Starts the node on first use and returns the same one afterwards. */
    suspend fun node(): FlickNode = lock.withLock {
        node ?: withContext(Dispatchers.Default) {
            startNode(loadOrCreateIdentity(), Build.MODEL, inbox.absolutePath, observer)
        }.also { node = it }
    }

    suspend fun stop() = lock.withLock {
        node?.close()
        node = null
    }

    private fun loadOrCreateIdentity(): String {
        val file = File(context.filesDir, "identity.pem")
        if (file.exists()) return file.readText()
        return generateIdentity().also { file.writeText(it) }
    }

    private val observer = object : ReceiveObserver {
        override fun onEvent(event: TransferEvent) {
            _events.tryEmit(event)
        }
    }
}

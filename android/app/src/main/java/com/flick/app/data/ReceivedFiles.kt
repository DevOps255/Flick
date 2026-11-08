package com.flick.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves a received file to the public Downloads folder, inside a folder named Flick.
 *
 * The Rust node writes into the app's own storage, which the file manager cannot show on Android 11
 * and later. A file that arrived there looked lost. After the copy the private file is deleted, so
 * the phone never keeps two copies of a large file.
 */
@Singleton
class ReceivedFiles @Inject constructor(@ApplicationContext private val context: Context) {

    /** A file now visible to every app. */
    class Published(val uri: Uri, val mime: String)

    /** Returns null when the copy failed. The private file is then kept, so nothing is lost. */
    suspend fun publish(privateFile: File): Published? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(privateFile.extension.lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, privateFile.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            // Hidden from other apps until the copy is complete.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        try {
            val output = checkNotNull(resolver.openOutputStream(target)) { "cannot write to Downloads" }
            output.use { out -> privateFile.inputStream().use { it.copyTo(out, COPY_BUFFER) } }
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            privateFile.delete()
            Published(target, mime)
        } catch (_: Exception) {
            resolver.delete(target, null, null)
            null
        }
    }

    /** Deletes a published file. Only files that Flick created can be deleted this way. */
    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    private companion object {
        const val FOLDER = "Flick"
        const val COPY_BUFFER = 1 shl 20
    }
}

package com.flick.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.flick.app.core.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Files the user picked and has not sent yet. Shared by the home and nearby screens. */
@Singleton
class SendSelection @Inject constructor(@ApplicationContext private val context: Context) {
    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    fun select(uris: List<Uri>) {
        _files.value = uris.map(::describe)
    }

    fun clear() {
        _files.value = emptyList()
    }

    private fun describe(uri: Uri): FileItem {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameCol >= 0) name = cursor.getString(nameCol)
                if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol)
            }
        }
        return FileItem(uri = uri.toString(), name = name, sizeBytes = size)
    }
}

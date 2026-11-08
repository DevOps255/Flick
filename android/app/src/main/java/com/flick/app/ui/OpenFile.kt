package com.flick.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens a file with the app the user picks. [uriString] is a content URI or a file URI.
 * A plain file URI cannot be handed to another app, so it goes through the FileProvider first.
 * When no app can open the type, nothing happens: the file is still where it was.
 */
internal fun openFile(context: Context, uriString: String, mime: String? = null) {
    val uri = Uri.parse(uriString)
    val shareable = if (uri.scheme == "file") {
        val path = uri.path ?: return
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    } else {
        uri
    }
    val type = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: context.contentResolver.getType(shareable)
        ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(shareable, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No app can open this type.
    } catch (_: SecurityException) {
        // The file moved or the permission lapsed.
    }
}

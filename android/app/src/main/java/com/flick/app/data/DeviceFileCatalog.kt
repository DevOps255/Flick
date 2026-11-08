package com.flick.app.data

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.flick.app.core.DeviceFile
import com.flick.app.core.FileCatalog
import com.flick.app.core.FileCategory
import com.flick.app.core.StorageSpace
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads what the phone holds.
 *
 * Images, videos and audio come from the media store, which is fast and gives thumbnails for free.
 * Downloads and documents are plain folders, so they are walked on disk: that needs the
 * "all files" permission, and without it the lists are empty. Apps come from the package manager
 * and are sent as their APK file.
 */
@Singleton
class DeviceFileCatalog @Inject constructor(@ApplicationContext private val context: Context) : FileCatalog {

    override suspend fun files(category: FileCategory): List<DeviceFile> = withContext(Dispatchers.IO) {
        when (category) {
            FileCategory.Images -> media(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, category)
            FileCategory.Videos -> media(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, category)
            FileCategory.Audio -> media(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, category)
            FileCategory.Apps -> apps()
            FileCategory.Downloads -> walk(downloadFolders(), category, DOWNLOAD_DEPTH) { true }
            FileCategory.Documents -> walk(storageRoots(), category, DOCUMENT_DEPTH) { it.extension.lowercase() in DOCUMENT_EXTENSIONS }
        }.sortedByDescending { it.modifiedMs }
    }

    override fun storage(): StorageSpace {
        val stat = StatFs(Environment.getDataDirectory().path)
        return StorageSpace(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
    }

    private fun media(collection: Uri, category: FileCategory): List<DeviceFile> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val found = ArrayList<DeviceFile>()
        try {
            context.contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val date = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext() && found.size < MAX_FILES) {
                    found += DeviceFile(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(id)).toString(),
                        name = cursor.getString(name) ?: continue,
                        sizeBytes = cursor.getLong(size),
                        modifiedMs = cursor.getLong(date) * 1000,
                        mime = cursor.getString(mime) ?: "application/octet-stream",
                        category = category,
                    )
                }
            }
        } catch (_: SecurityException) {
            // The media permission was refused. The screen asks for it and lists again.
        }
        return found
    }

    /**
     * Every app that has a launcher icon, system apps included, plus every app the user installed.
     * Listing them all needs the QUERY_ALL_PACKAGES permission. Without it Android hides most apps.
     * An app that came as several APK files is sent as its main APK only, which cannot be installed alone.
     */
    private fun apps(): List<DeviceFile> {
        val packages = context.packageManager
        return packages.getInstalledApplications(0)
            .filter { info ->
                val userInstalled = info.flags and ApplicationInfo.FLAG_SYSTEM == 0
                userInstalled || packages.getLaunchIntentForPackage(info.packageName) != null
            }
            .mapNotNull { info ->
                val apk = File(info.sourceDir)
                if (!apk.canRead()) return@mapNotNull null
                DeviceFile(
                    uri = Uri.fromFile(apk).toString(),
                    name = packages.getApplicationLabel(info).toString().replace(UNSAFE_NAME, "_") + ".apk",
                    sizeBytes = apk.length(),
                    modifiedMs = apk.lastModified(),
                    mime = APK_MIME,
                    category = FileCategory.Apps,
                    packageName = info.packageName,
                )
            }
    }

    private fun walk(roots: List<File>, category: FileCategory, depth: Int, accept: (File) -> Boolean): List<DeviceFile> {
        val found = ArrayList<DeviceFile>()
        val mimes = MimeTypeMap.getSingleton()
        fun visit(dir: File, level: Int) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (found.size >= MAX_FILES) return
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    // Android keeps the private folders of every app here. They are not documents.
                    if (level < depth && child.name != "Android") visit(child, level + 1)
                } else if (accept(child)) {
                    found += DeviceFile(
                        uri = Uri.fromFile(child).toString(),
                        name = child.name,
                        sizeBytes = child.length(),
                        modifiedMs = child.lastModified(),
                        mime = mimes.getMimeTypeFromExtension(child.extension.lowercase()) ?: "application/octet-stream",
                        category = category,
                    )
                }
            }
        }
        roots.forEach { visit(it, 0) }
        return found
    }

    /** The main storage and every SD card, found through the app folders Android creates on each volume. */
    private fun storageRoots(): List<File> {
        val main = Environment.getExternalStorageDirectory()
        val others = context.getExternalFilesDirs(null).filterNotNull().map { File(it.path.substringBefore("/Android/")) }
        return (listOf(main) + others).distinct().filter { it.isDirectory }
    }

    private fun downloadFolders(): List<File> =
        storageRoots().flatMap { root -> listOf(File(root, "Download"), File(root, "Downloads")) }.filter { it.isDirectory }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val MAX_FILES = 5_000
        const val DOWNLOAD_DEPTH = 3
        const val DOCUMENT_DEPTH = 6
        val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|]")
        val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "odt", "ods", "epub", "md", "rtf")
    }
}

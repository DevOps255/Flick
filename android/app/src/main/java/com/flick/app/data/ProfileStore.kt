package com.flick.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flick.app.core.Profile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.profileDataStore by preferencesDataStore(name = "profile")

/** Persists the local user's name, photo and Flick ID. */
@Singleton
class ProfileStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val nameKey = stringPreferencesKey("name")
    private val idKey = stringPreferencesKey("flick_id")
    private val photoKey = stringPreferencesKey("photo_path")

    val profile: Flow<Profile> = context.profileDataStore.data.map { prefs ->
        Profile(
            name = prefs[nameKey] ?: Build.MODEL,
            flickId = prefs[idKey] ?: "flk#-----",
            photoPath = prefs[photoKey]?.takeIf { File(it).exists() },
        )
    }

    /** The name other phones see. Read when the node starts. */
    suspend fun currentName(): String = profile.first().name

    /** Generates the Flick ID on first launch. Later calls change nothing. */
    suspend fun ensureCreated() {
        context.profileDataStore.edit { prefs ->
            if (prefs[idKey] == null) prefs[idKey] = "flk#" + Random.nextInt(10_000, 100_000)
        }
    }

    /** Saves the name. A blank name is ignored, and a long one is cut. */
    suspend fun setName(name: String) {
        val clean = name.trim().take(MAX_NAME_LENGTH)
        if (clean.isEmpty()) return
        context.profileDataStore.edit { it[nameKey] = clean }
    }

    /**
     * Saves a picked photo as a small JPEG in the app's private storage, so the original can move or
     * disappear without breaking the profile. The photo stays on this phone: it is never sent.
     */
    suspend fun setPhoto(source: Uri) {
        val saved = withContext(Dispatchers.IO) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > PHOTO_PIXELS) {
                    val scale = PHOTO_PIXELS.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            // A new file name each time, so the image is reloaded instead of served from a cache.
            val file = File(context.filesDir, "profile-${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file
        }
        replacePhoto(saved.absolutePath)
    }

    suspend fun removePhoto() = replacePhoto(null)

    private suspend fun replacePhoto(path: String?) {
        val previous = context.profileDataStore.data.first()[photoKey]
        context.profileDataStore.edit { prefs -> if (path == null) prefs.remove(photoKey) else prefs[photoKey] = path }
        if (previous != null && previous != path) withContext(Dispatchers.IO) { File(previous).delete() }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 24
        const val PHOTO_PIXELS = 512
    }
}

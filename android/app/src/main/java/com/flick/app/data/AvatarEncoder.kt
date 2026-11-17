package com.flick.app.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Makes the small picture that other phones see: the profile photo cut down to 160 pixels, as a JPEG of a few
 * kilobytes. Other phones fetch it over the encrypted link, so it must stay small and fast to send.
 */
object AvatarEncoder {
    private const val SIDE = 160
    private const val QUALITY = 80

    /** The picture for the photo at [path], or null when the photo cannot be read. */
    fun thumbnail(path: String): ByteArray? = runCatching {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > SIDE) {
                val scale = SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
    }.getOrNull()
}

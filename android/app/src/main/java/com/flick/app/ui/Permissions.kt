package com.flick.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** The permissions Flick asks for, which depend on the Android version. */
object Permissions {
    /** What Bluetooth needs: three permissions on Android 12 and later, location before that. */
    fun bluetooth(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Everything, in one prompt: Bluetooth, the Wi-Fi Direct link, and notifications for the transfer. */
    fun all(): Array<String> {
        val wifiDirect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        return (bluetooth() + wifiDirect + notifications).distinct().toTypedArray()
    }

    fun hasBluetooth(context: Context): Boolean =
        bluetooth().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** What reading photos, videos and music needs: three permissions on Android 13 and later, one before that. */
    fun media(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasMedia(context: Context): Boolean =
        media().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } || hasAllFiles(context)

    /**
     * Whether Flick may read every folder, which listing Downloads and Documents needs. Android 11 and
     * later grant this only from a settings page, not from a prompt. Before that the storage permission is enough.
     */
    fun hasAllFiles(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** The settings page where the user allows Flick to read every folder. */
    fun allFilesSettings(context: Context): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
}

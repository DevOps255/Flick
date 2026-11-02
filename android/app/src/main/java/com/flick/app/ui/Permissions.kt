package com.flick.app.ui

import android.Manifest
import android.content.Context
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
}

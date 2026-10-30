package com.flick.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Wi-Fi is on.
 *
 * Since Android 10 an app cannot switch Wi-Fi on by itself, the call is refused. The most an app
 * can do is notice that it is off and open the system panel where one tap turns it on.
 */
@Singleton
class WifiStatus @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(WifiManager::class.java)
    private val _enabled = MutableStateFlow(manager?.isWifiEnabled == true)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                _enabled.value = state == WifiManager.WIFI_STATE_ENABLED
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun isEnabled(): Boolean = manager?.isWifiEnabled == true
}

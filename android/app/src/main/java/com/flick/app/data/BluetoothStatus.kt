package com.flick.app.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether Bluetooth is on. Flick can ask for it with the system dialog, it cannot switch it on silently. */
@Singleton
class BluetoothStatus @Inject constructor(@ApplicationContext context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val _enabled = MutableStateFlow(adapter?.isEnabled == true)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                _enabled.value = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

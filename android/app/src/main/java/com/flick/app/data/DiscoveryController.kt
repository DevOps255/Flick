package com.flick.app.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.flick.app.core.PeerDiscovery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the Bluetooth discovery running from the moment the app opens.
 *
 * It runs while four things hold: the user left Nearby on, Bluetooth is allowed, Bluetooth is
 * switched on, and the app is on screen. It stops when the app goes to the background, so
 * Bluetooth is not used while nobody looks. When the user switches Bluetooth on from the banner,
 * the discovery starts at once, because the switch is one of the four things it watches.
 */
@Singleton
class DiscoveryController @Inject constructor(
    private val discovery: PeerDiscovery,
    private val problems: Problems,
    sensors: SensorFlickSource,
    bluetooth: BluetoothStatus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + problems.handler("Nearby search"))
    private val _enabled = MutableStateFlow(true)
    private val _permissionsGranted = MutableStateFlow(false)
    private val onScreen = MutableStateFlow(false)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                onScreen.value = true
            }

            override fun onStop(owner: LifecycleOwner) {
                onScreen.value = false
            }
        })
        scope.launch {
            combine(_enabled, _permissionsGranted, onScreen, bluetooth.enabled) { on, allowed, visible, radioOn ->
                on && allowed && visible && radioOn
            }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        try {
                            discovery.start()
                            awaitCancellation()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Includes errors such as a missing native library, which are not Exceptions.
                            problems.report("Nearby search", e)
                        } finally {
                            withContext(NonCancellable) { runCatching { discovery.stop() } }
                        }
                    }
                }
        }
    }

    init {
        // The pairing gesture is read while the app is on screen, whatever screen is open.
        scope.launch {
            onScreen.collectLatest { visible ->
                if (visible) sensors.tilts().collect { discovery.onPairingGesture() }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }

    fun setPermissionsGranted(value: Boolean) {
        _permissionsGranted.value = value
    }
}

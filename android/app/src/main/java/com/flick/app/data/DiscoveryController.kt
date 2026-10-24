package com.flick.app.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.flick.app.core.PeerDiscovery
import javax.inject.Inject
import javax.inject.Singleton
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
 * Keeps the ultrasonic discovery running from the moment the app opens.
 *
 * It runs while three things hold: the user left Nearby on, the microphone is
 * allowed, and the app is on screen. It stops when the app goes to the
 * background, so the speaker and microphone are not used while nobody looks.
 */
@Singleton
class DiscoveryController @Inject constructor(private val discovery: PeerDiscovery) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _enabled = MutableStateFlow(true)
    private val _micGranted = MutableStateFlow(false)
    private val onScreen = MutableStateFlow(false)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val micGranted: StateFlow<Boolean> = _micGranted.asStateFlow()

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
            combine(_enabled, _micGranted, onScreen) { on, mic, visible -> on && mic && visible }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        discovery.start()
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { discovery.stop() }
                        }
                    }
                }
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }

    fun setMicGranted(value: Boolean) {
        _micGranted.value = value
    }
}

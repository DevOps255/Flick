package com.flick.app.ui

import androidx.lifecycle.ViewModel
import com.flick.app.data.DiscoveryController
import com.flick.app.data.ReceiveCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Holds what the whole app needs to show, whatever screen is open. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val receive: ReceiveCoordinator,
    private val discovery: DiscoveryController,
) : ViewModel() {
    val incoming = receive.incoming

    fun dismissIncoming() = receive.dismiss()

    fun setMicGranted(granted: Boolean) = discovery.setMicGranted(granted)
}

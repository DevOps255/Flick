package com.flick.app.ui

import androidx.lifecycle.ViewModel
import com.flick.app.data.DiscoveryController
import com.flick.app.data.Problems
import com.flick.app.core.PeerDiscovery
import com.flick.app.data.ReceiveCoordinator
import com.flick.app.data.SendSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Holds what the whole app needs to show, whatever screen is open. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val receive: ReceiveCoordinator,
    private val discovery: DiscoveryController,
    private val problems: Problems,
    peerDiscovery: PeerDiscovery,
    selection: SendSelection,
) : ViewModel() {
    val incoming = receive.incoming

    /** Emits when the user paired with a phone and the link to it is really up. The screen plays the AirDrop effect then. */
    val associations = peerDiscovery.associations

    /** The files waiting to be sent, shown in the spotlight during the effect. */
    val selectedFiles = selection.files

    /** The last failure, shown in a banner on every screen. */
    val problem = problems.current

    fun dismissProblem() = problems.dismiss()

    fun dismissIncoming() = receive.dismiss()

    fun setPermissionsGranted(granted: Boolean) = discovery.setPermissionsGranted(granted)
}

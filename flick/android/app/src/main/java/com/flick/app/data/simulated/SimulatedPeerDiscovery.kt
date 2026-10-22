package com.flick.app.data.simulated

import com.flick.app.core.Peer
import com.flick.app.core.PeerDiscovery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stand-in used until the ultrasonic layer is wired in. It lets the UI be
 * built and tested on one phone. Replace the binding in `EngineModule`.
 */
@Singleton
class SimulatedPeerDiscovery @Inject constructor() : PeerDiscovery {
    private val current = MutableStateFlow<List<Peer>>(emptyList())

    override val peers: Flow<List<Peer>> = current

    override suspend fun start() {
        current.value = listOf(
            Peer("p1", "Ashley Nelson", "flk#11245", isFriend = true, proximity = 0.9f),
            Peer("p2", "Gofar Badman", "flk#66666", isFriend = false, proximity = 0.55f),
            Peer("p3", "David Okoro", "flk#30418", isFriend = false, proximity = 0.35f),
            Peer("p4", "Greyhold", "flk#77120", isFriend = true, proximity = 0.2f),
        )
    }

    override suspend fun stop() {
        current.value = emptyList()
    }
}

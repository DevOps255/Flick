package com.flick.app.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Finds devices close enough to exchange files with.
 *
 * The real implementation listens for the ultrasonic announcement and keeps
 * the list current. Peers that stop being heard disappear from [peers].
 */
interface PeerDiscovery {
    val peers: Flow<List<Peer>>

    suspend fun start()

    suspend fun stop()

    /** Emits a phone once the user paired with it and it answered over TLS, so the link is really up. */
    val associations: Flow<Peer> get() = emptyFlow()

    /** The user made the pairing gesture on this phone. */
    fun onPairingGesture() = Unit
}

/**
 * Moves files to a peer. The returned flow completes after the last file,
 * or after emitting a [TransferStatus.Failed] snapshot.
 */
interface TransferEngine {
    fun send(peer: Peer, files: List<FileItem>): Flow<TransferProgress>
}

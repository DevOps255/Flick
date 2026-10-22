package com.flick.app.core

import kotlinx.coroutines.flow.Flow

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
}

/**
 * Moves files to a peer. The returned flow completes after the last file,
 * or after emitting a [TransferStatus.Failed] snapshot.
 */
interface TransferEngine {
    fun send(peer: Peer, files: List<FileItem>): Flow<TransferProgress>
}

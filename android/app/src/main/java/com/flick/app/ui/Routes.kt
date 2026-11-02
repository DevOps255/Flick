package com.flick.app.ui

import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object NearbyRoute

@Serializable
object ProfileRoute

@Serializable
data class TransferRoute(
    /** Ids of the recipients, separated by commas. One id unless the user chose to share with several phones. */
    val peerIds: String,
)

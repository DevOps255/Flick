package com.flick.app.ui

import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object NearbyRoute

@Serializable
object ProfileRoute

@Serializable
data class TransferRoute(val peerId: String)

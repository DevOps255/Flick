package com.flick.app.data.acoustic

import java.net.Inet4Address
import java.net.NetworkInterface

/** Reads the IPv4 addresses this phone has on networks a neighbouring phone could reach. */
object LocalAddress {

    /** One address on one network interface. */
    class Link(val ip: ByteArray, val prefixLength: Int, val interfaceName: String) {
        /** True for the interface Android creates while this phone is in a Wi-Fi Direct group. */
        val isDirect: Boolean get() = interfaceName.startsWith("p2p")
    }

    /**
     * Private addresses on Wi-Fi, hotspot and Wi-Fi Direct interfaces.
     * Mobile data and VPN interfaces are skipped because a neighbouring phone cannot reach them.
     */
    fun links(): List<Link> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && isLocalLink(it.name) }
        .flatMap { nic ->
            nic.interfaceAddresses
                .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
                .map { Link(it.address.address, it.networkPrefixLength.toInt(), nic.name) }
        }

    /**
     * The address to announce. Inside a Wi-Fi Direct group the group address wins, because
     * that is the only network the other phone shares with this one.
     */
    fun ipv4(): ByteArray? {
        val all = links()
        return (all.firstOrNull { it.isDirect } ?: all.firstOrNull())?.ip
    }

    private fun isLocalLink(name: String) =
        name.startsWith("wlan") || name.startsWith("p2p") || name.startsWith("ap") || name.startsWith("swlan")
}

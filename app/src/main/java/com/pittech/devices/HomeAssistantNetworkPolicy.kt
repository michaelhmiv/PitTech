package com.pittech.devices

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Cleartext dev/LAN sessions cannot follow DNS rebinding onto a public address. */
internal object HomeAssistantLanDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isLan(it) }) throw UnknownHostException("Local Home Assistant hostname resolved outside the LAN")
        return addresses
    }
    fun isLan(address: InetAddress): Boolean = address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
        (address is java.net.Inet6Address && (address.address[0].toInt() and 254) == 252)
}

package com.screenshare.send

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fallback discovery for networks where multicast is filtered or phones/TVs sit on
 * different subnets: TCP-connect probe of the DLNA port across the local /24.
 * Restricted to the Wi-Fi interface's own subnet; results capped at 20.
 */
object SubnetScanner {
    private val pool = Executors.newFixedThreadPool(64)

    fun scan(onResult: (List<String>) -> Unit) {
        val wifiIp = wifiInterfaceIp()
        val bases = LinkedHashSet<String>()
        if (wifiIp != null) {
            val parts = wifiIp.split(".")
            if (parts.size == 4) bases.add("${parts[0]}.${parts[1]}.${parts[2]}.")
        }
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pending = AtomicInteger(bases.size * 254)
        if (pending.get() == 0) { onResult(emptyList()); return }
        for (base in bases) {
            for (i in 1..254) {
                val ip = base + i
                pool.execute {
                    try {
                        val s = Socket()
                        s.connect(InetSocketAddress(ip, 49152), 250)
                        found.add(ip)
                        s.close()
                    } catch (_: Exception) {
                    }
                    if (pending.decrementAndGet() == 0) {
                        onResult(found.distinct().take(20))
                    }
                }
            }
        }
        pool.execute {
            TimeUnit.SECONDS.sleep(6)
            onResult(found.distinct().take(20))
        }
    }

    private fun wifiInterfaceIp(): String? = try {
        var best: String? = null
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val nif = en.nextElement()
            val name = nif.name.lowercase()
            if (!nif.isUp || nif.isLoopback) continue
            if (!(name.startsWith("wlan") || name.startsWith("swlan"))) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address && !a.isLoopbackAddress) { best = a.hostAddress; break }
            }
            if (best != null) break
        }
        best
    } catch (_: Exception) { null }
}

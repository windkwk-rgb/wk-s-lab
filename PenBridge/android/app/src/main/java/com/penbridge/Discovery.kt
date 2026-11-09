package com.penbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 같은 Wi‑Fi에 있는 PenBridge PC를 찾는다. 백그라운드 스레드에서 호출할 것.
 *
 * 두 가지를 동시에 한다:
 *  1) UDP 브로드캐스트 (빠르지만 공유기·방화벽에 따라 막힐 수 있음)
 *  2) 같은 네트워크(/24)의 주소에 하나씩 TCP 접속해서 "PenBridge 맞아요?" 확인 (느리지만 확실)
 */
object Discovery {
    data class Found(val name: String, val address: String)

    fun search(context: Context, timeoutMs: Int = 2500): List<Found> {
        val results = ConcurrentHashMap<String, Found>()
        val locals = localAddresses(context)

        val udp = Thread { try { udpSearch(locals, results, timeoutMs.coerceAtMost(1500)) } catch (_: Exception) {} }
        udp.isDaemon = true
        udp.start()
        try { tcpSweep(locals, results, timeoutMs) } catch (_: Exception) {}
        udp.join(2000)

        return results.values.sortedBy { it.name.lowercase() }
    }

    /** 특정 PC 이름을 찾는다 (IP가 바뀌었을 때) */
    fun findByName(context: Context, name: String): Found? =
        search(context).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** 주소 하나에 접속해서 PenBridge인지 확인. 맞으면 PC 이름(옛 버전은 주소) 반환 */
    fun probe(address: String, timeoutMs: Int = 400): String? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(address, Protocol.PORT), timeoutMs)
                s.soTimeout = 1000
                s.getOutputStream().apply { write(Protocol.discover()); flush() }
                val buf = ByteArray(Protocol.PACKET_SIZE)
                try {
                    DataInputStream(s.getInputStream()).readFully(buf)
                    if (buf[0] == Protocol.DISCOVER_REPLY) Protocol.readName(buf) ?: address else address
                } catch (_: Exception) {
                    address   // 포트는 열려 있음 → 옛 버전 PenBridge
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class Local(val address: Inet4Address, val prefix: Int)

    private fun udpSearch(locals: List<Local>, out: MutableMap<String, Found>, timeoutMs: Int) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 300
            val query = Protocol.DISCOVER_QUERY.toByteArray()
            val targets = locals.mapNotNull { broadcastOf(it) } + InetAddress.getByName("255.255.255.255")
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (t in targets) {
                        try { socket.send(DatagramPacket(query, query.size, t, Protocol.DISCOVERY_PORT)) } catch (_: Exception) {}
                    }
                    nextSend = System.currentTimeMillis() + 500
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val text = String(p.data, 0, p.length)
                    val addr = p.address?.hostAddress
                    if (addr != null && text.startsWith(Protocol.DISCOVER_REPLY_TEXT)) {
                        out[addr] = Found(text.split('|').getOrNull(1) ?: addr, addr)
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
    }

    private fun tcpSweep(locals: List<Local>, out: MutableMap<String, Found>, timeoutMs: Int) {
        val pool = Executors.newFixedThreadPool(64)
        try {
            for (l in locals) {
                val ip = toInt(l.address)
                // 너무 큰 네트워크는 내 주소 주변 /24만
                val prefix = l.prefix.coerceAtLeast(24)
                val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
                val base = ip and mask
                val count = (1 shl (32 - prefix)) - 2
                for (i in 1..count) {
                    val target = base + i
                    if (target == ip) continue
                    val addr = fromInt(target)
                    pool.execute {
                        if (out.containsKey(addr)) return@execute
                        val name = probe(addr) ?: return@execute
                        out.putIfAbsent(addr, Found(name, addr))
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun localAddresses(context: Context): List<Local> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val net = cm.activeNetwork ?: return emptyList()
        val caps = cm.getNetworkCapabilities(net)
        if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return emptyList()
        val props = cm.getLinkProperties(net) ?: return emptyList()
        return props.linkAddresses.mapNotNull { la ->
            val a = la.address as? Inet4Address ?: return@mapNotNull null
            if (la.prefixLength !in 8..31) return@mapNotNull null
            Local(a, la.prefixLength)
        }
    }

    private fun broadcastOf(l: Local): InetAddress? {
        val ip = toInt(l.address)
        val mask = -1 shl (32 - l.prefix)
        return InetAddress.getByName(fromInt(ip or mask.inv()))
    }

    private fun toInt(a: Inet4Address): Int =
        a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

    private fun fromInt(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
}

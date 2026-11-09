package com.penbridge

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PC와의 TCP 연결. 끊기면 자동으로 다시 연결한다.
 * 펜 패킷은 큐에 넣기만 하면 전송 스레드가 바로 보낸다(UI 스레드를 막지 않음).
 */
class PenConnection(
    private val host: String,
    private val pin: Int,
    private val versionCode: Int,
    private val autoUpdate: Boolean,
    private val listener: Listener,
) {
    enum class Status { CONNECTING, CONNECTED, RECONNECTING, REJECTED, CLOSED }

    interface Listener {
        /** 백그라운드 스레드에서 호출됨 */
        fun onStatus(status: Status, detail: String? = null)
        /** PC 화면 비율(가로/세로). 0이면 태블릿 전체 사용 */
        fun onAspect(aspect: Float)
        fun onLatency(ms: Long)
        /** PC에 새 태블릿 앱이 있음 */
        fun onUpdateOffer(versionCode: Int, size: Long)
        /** 노트북에서 지우개가 켜졌는지 (툴바 버튼 표시) */
        fun onToolState(eraser: Boolean)
    }

    private val queue = ArrayBlockingQueue<ByteArray>(512)
    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    @Volatile var viewWidth = 0
    @Volatile var viewHeight = 0
    @Volatile private var pingSentAt = 0L
    @Volatile var failures = 0
        private set
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ runLoop() }, "PenBridge connection").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    /** 패킷 전송 예약. 너무 밀리면(네트워크가 느리면) 오래된 것부터 버린다. */
    fun send(packet: ByteArray) {
        if (!queue.offer(packet)) {
            queue.poll()
            queue.offer(packet)
        }
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) {}
        thread?.interrupt()
    }

    private fun runLoop() {
        var first = true
        while (!closed) {
            listener.onStatus(if (first) Status.CONNECTING else Status.RECONNECTING)
            first = false
            try {
                session()
            } catch (_: RejectedException) {
                listener.onStatus(Status.REJECTED)
                closed = true
                break
            } catch (e: Exception) {
                if (closed) break
                failures++
                listener.onStatus(Status.RECONNECTING, e.message)
            } finally {
                try { socket?.close() } catch (_: IOException) {}
                socket = null
            }
            if (!closed) try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        }
        listener.onStatus(Status.CLOSED)
    }

    private fun session() {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, Protocol.PORT), 3000)
        s.soTimeout = 6000
        val out = s.getOutputStream()
        val input = DataInputStream(s.getInputStream())

        out.write(Protocol.hello(pin, viewWidth, viewHeight, versionCode, if (autoUpdate) Protocol.HELLO_AUTO_UPDATE else 0))
        out.flush()

        // 첫 응답: ACCEPT 또는 REJECT
        val buf = ByteArray(Protocol.PACKET_SIZE)
        input.readFully(buf)
        if (buf[0] == Protocol.REJECT) throw RejectedException()
        if (buf[0] != Protocol.ACCEPT) throw IOException("알 수 없는 응답")

        queue.clear()   // 연결 전에 쌓인 오래된 입력은 버림
        failures = 0
        listener.onStatus(Status.CONNECTED, Protocol.readName(buf))   // detail = PC 이름 (1.2 이상)

        // 수신 스레드: CONFIG(화면 비율), PONG(지연 측정)
        val reader = Thread({
            try {
                val rb = ByteArray(Protocol.PACKET_SIZE)
                while (!closed) {
                    input.readFully(rb)
                    when (rb[0]) {
                        Protocol.CONFIG -> listener.onAspect(Protocol.readFloat(rb, 4))
                        Protocol.UPDATE_OFFER -> listener.onUpdateOffer(Protocol.readInt(rb, 4), Protocol.readLong(rb, 8))
                        Protocol.TOOL_STATE -> listener.onToolState(rb[1].toInt() != 0)
                        Protocol.PONG -> if (pingSentAt != 0L) {
                            listener.onLatency((System.nanoTime() - pingSentAt) / 1_000_000)
                        }
                    }
                }
            } catch (_: Exception) {
                try { s.close() } catch (_: IOException) {}
            }
        }, "PenBridge reader").apply { isDaemon = true; start() }

        try {
            writeLoop(out, s)
        } finally {
            reader.interrupt()
        }
    }

    private fun writeLoop(out: OutputStream, s: Socket) {
        var lastPing = 0L
        while (!closed && !s.isClosed) {
            val packet = queue.poll(500, TimeUnit.MILLISECONDS)
            if (packet != null) {
                out.write(packet)
                // 큐에 이미 쌓인 것도 한 번에 보냄
                while (true) {
                    val more = queue.poll() ?: break
                    out.write(more)
                }
                out.flush()
            }
            val now = System.nanoTime()
            if (now - lastPing > 1_000_000_000L) {
                pingSentAt = now
                out.write(Protocol.ping())
                out.flush()
                lastPing = now
            }
        }
        if (!closed) throw IOException("연결이 끊겼어요")
    }

    private class RejectedException : Exception()
}

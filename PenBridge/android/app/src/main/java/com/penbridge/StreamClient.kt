package com.penbridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 액정 타블렛 모드: 노트북 화면을 받아 온다 (펜 연결과는 별도 연결).
 * 한 장 받아서 풀고 → "받았어요"를 보내면 노트북이 다음 장을 보낸다.
 * 끊기면 자동으로 다시 연결한다.
 */
class StreamClient(
    private val host: String,
    private val pin: Int,
    private val maxWidth: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** 백그라운드 스레드에서 호출. 받은 bitmap은 다음 프레임에서 재사용될 수 있으니 바로 그릴 것 */
        fun onFrame(bitmap: Bitmap)
        fun onStreamState(state: State)
    }

    enum class State { CONNECTING, RUNNING, DENIED, STOPPED }

    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    // 두 장을 번갈아 써서, 그리는 중인 그림을 덮어쓰지 않게 함
    private val pool = arrayOfNulls<Bitmap>(2)
    private var poolIndex = 0

    fun start() {
        thread = Thread({ loop() }, "PenBridge stream").apply { isDaemon = true; start() }
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) {}
        thread?.interrupt()
    }

    private fun loop() {
        while (!closed) {
            listener.onStreamState(State.CONNECTING)
            try {
                if (!session()) {
                    listener.onStreamState(State.DENIED)
                    return
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: IOException) {}
                socket = null
            }
            if (!closed) try { Thread.sleep(1500) } catch (_: InterruptedException) {}
        }
        listener.onStreamState(State.STOPPED)
    }

    /** false = 노트북이 거절 (화면 보내기 꺼짐 / PIN) */
    private fun session(): Boolean {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.receiveBufferSize = 1 shl 20
        s.connect(InetSocketAddress(host, Protocol.PORT), 3000)
        s.soTimeout = 8000
        val out = s.getOutputStream()
        out.write(Protocol.stream(pin, maxWidth))
        out.flush()
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
        val header = ByteArray(Protocol.PACKET_SIZE)
        input.readFully(header)
        if (header[0] == Protocol.REJECT) return false
        if (header[0] != Protocol.ACCEPT) throw IOException("알 수 없는 응답")
        listener.onStreamState(State.RUNNING)

        var data = ByteArray(512 * 1024)
        val ack = Protocol.frameAck()
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        while (!closed) {
            input.readFully(header)
            if (header[0] != Protocol.FRAME) continue
            val len = Protocol.readInt(header, 4)
            if (len <= 0 || len > 32 * 1024 * 1024) throw IOException("잘못된 크기")
            if (data.size < len) data = ByteArray(len + len / 4)
            input.readFully(data, 0, len)

            // 다음 장을 미리 요청 (풀고 그리는 동안 노트북은 캡처를 시작)
            out.write(ack)
            out.flush()

            val w = Protocol.readInt(header, 8)
            val h = Protocol.readInt(header, 12)
            poolIndex = (poolIndex + 1) % 2
            val reuse = pool[poolIndex]?.takeIf { it.width == w && it.height == h && it.isMutable }
            opts.inBitmap = reuse
            val bmp = try {
                BitmapFactory.decodeByteArray(data, 0, len, opts)
            } catch (_: IllegalArgumentException) {
                opts.inBitmap = null
                BitmapFactory.decodeByteArray(data, 0, len, opts)
            } ?: continue
            pool[poolIndex] = bmp
            listener.onFrame(bmp)
        }
        return true
    }
}

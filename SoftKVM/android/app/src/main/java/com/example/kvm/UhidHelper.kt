package com.example.kvm

import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * 태블릿 안에서 '셸 권한'으로 실행되는 작은 도우미 (메인 PC가 무선 디버깅으로 실행해 줌).
 * 리눅스 /dev/uhid 로 가상 USB 키보드·마우스를 만들어서, 태블릿과 모든 앱(지포스 나우 등)이
 * 실제로 꽂은 마우스·키보드로 인식하게 함.
 *
 * 앱(KVM)은 127.0.0.1:24801 로 접속해 비밀 토큰을 보낸 뒤 [길이 1바이트][HID 보고서] 를 계속 보냄.
 */
object UhidHelper {
    const val PORT = 24801

    // 키보드 장치: 표준 USB 키보드 그대로 (보고서 번호 없음, LED 포함) — scrcpy 등에서 검증된 형태
    // 예전 버전은 미디어 키를 같이 넣어서 태블릿이 '조이스틱'으로도 분류해 글자 키가 무시됐음
    val KEYBOARD: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x75, 0x08, 0x95, 0x01, 0x81, 0x01,
        0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x75, 0x01, 0x95, 0x05, 0x91, 0x02,
        0x75, 0x03, 0x95, 0x01, 0x91, 0x01,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x91, 0x15, 0x00, 0x26, 0x91, 0x00, 0x75, 0x08, 0x95, 0x06, 0x81, 0x00,   // 0x90·0x91 = 한/영·한자 키
        0xC0,
    ).map { it.toByte() }.toByteArray()

    // 마우스 장치: 보고서 2 = 버튼·X16·Y16·휠·가로휠 (7바이트)
    // 키보드와 마우스를 한 장치로 만들면 일부 기기에서 키보드가 무시돼서 따로 만듦
    val MOUSE: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
    ).map { it.toByte() }.toByteArray()

    private const val UHID_DESTROY = 1
    private const val UHID_CREATE2 = 11
    private const val UHID_INPUT2 = 12
    private lateinit var kbd: FileDescriptor
    private lateinit var mouse: FileDescriptor
    private val lock = Any()

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.getOrNull(0) ?: ""
        kbd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
        mouse = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
        create(kbd, "KVM Keyboard", 0x4B56, KEYBOARD)
        create(mouse, "KVM Mouse", 0x4B57, MOUSE)
        println("KVM uhid ready")
        // 커널이 보내는 알림(시작·열림·LED 등)을 비워 둠
        for (fd in listOf(kbd, mouse)) thread(isDaemon = true) {
            val buf = ByteArray(4380)
            while (true) {
                try { Os.read(fd, buf, 0, buf.size) } catch (_: Exception) { break }
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { destroy(kbd); destroy(mouse) })
        val server = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
        while (true) {
            val s = server.accept()
            thread(isDaemon = true) { serve(s, token) }
        }
    }

    private fun serve(s: Socket, token: String) {
        try {
            s.tcpNoDelay = true
            val input = DataInputStream(s.getInputStream().buffered())
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return
                if (c == '\n'.code) break
                line.append(c.toChar())
                if (line.length > 100) return
            }
            if (line.toString() != token) { s.close(); return }
            s.getOutputStream().write("OK\n".toByteArray()); s.getOutputStream().flush()
            val buf = ByteArray(64)
            while (true) {
                val n = input.read()
                if (n < 0) break
                if (n == 0 || n > buf.size) continue
                input.readFully(buf, 0, n)
                send(buf, n)
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
            // 연결이 끊기면 눌린 키·버튼을 모두 뗌
            send(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0), 9)
            send(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0), 8)
        }
    }

    private fun create(fd: FileDescriptor, nameStr: String, product: Int, desc: ByteArray) {
        val b = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + 4096).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(UHID_CREATE2)
        val name = nameStr.toByteArray()
        b.put(name); b.put(ByteArray(128 - name.size))
        val phys = "kvm/$product".toByteArray()
        b.put(phys); b.put(ByteArray(64 - phys.size))
        b.put(ByteArray(64))
        b.putShort(desc.size.toShort())
        b.putShort(0x03)                // USB 장치로 표시
        b.putInt(0x1209); b.putInt(product); b.putInt(1); b.putInt(0)
        b.put(desc); b.put(ByteArray(4096 - desc.size))
        write(fd, b.array())
    }

    private fun destroy(fd: FileDescriptor) {
        val b = ByteBuffer.allocate(4 + 4380).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(UHID_DESTROY)
        try { write(fd, b.array()) } catch (_: Exception) {}
    }

    /** 앱이 보낸 [번호][내용]: 1 = 키보드(번호 떼고 보냄), 2 = 마우스(번호 포함), 3 = 미디어 키(사용 안 함) */
    private fun send(report: ByteArray, len: Int) {
        val id = report[0].toInt()
        val fd: FileDescriptor
        val off: Int
        when (id) {
            1 -> { fd = kbd; off = 1 }
            2 -> { fd = mouse; off = 0 }
            else -> return
        }
        val b = ByteBuffer.allocate(4 + 2 + 4096).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(UHID_INPUT2)
        b.putShort((len - off).toShort())
        b.put(report, off, len - off)
        try { write(fd, b.array()) } catch (_: Exception) {}
    }

    private fun write(fd: FileDescriptor, data: ByteArray) {
        synchronized(lock) {
            var off = 0
            while (off < data.size) off += Os.write(fd, data, off, data.size - off)
        }
    }
}

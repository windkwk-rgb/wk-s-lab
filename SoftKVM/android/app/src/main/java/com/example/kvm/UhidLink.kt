package com.example.kvm

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 앱 → 셸 도우미(UhidHelper) 연결. 연결되면 마우스·키보드를 '진짜 장치' 신호로 보냄.
 * 모든 전송은 별도 스레드 하나에서 순서대로 처리 (화면 스레드에서 네트워크 금지).
 */
class UhidLink(private val log: (String) -> Unit, private val onState: (Boolean) -> Unit) {

    companion object {
        private val VK_TO_HID: Map<Int, Int> = HashMap<Int, Int>().apply {
            for (i in 0..25) put(0x41 + i, 0x04 + i)
            for (i in 1..9) put(0x30 + i, 0x1E + i - 1)
            put(0x30, 0x27)
            for (i in 0..11) put(0x70 + i, 0x3A + i)
            for (i in 1..9) put(0x60 + i, 0x59 + i - 1)
            putAll(mapOf(
                0x0D to 0x28, 0x1B to 0x29, 0x08 to 0x2A, 0x09 to 0x2B, 0x20 to 0x2C,
                0xBD to 0x2D, 0xBB to 0x2E, 0xDB to 0x2F, 0xDD to 0x30, 0xDC to 0x31,
                0xBA to 0x33, 0xDE to 0x34, 0xC0 to 0x35, 0xBC to 0x36, 0xBE to 0x37, 0xBF to 0x38,
                0x14 to 0x39, 0x2C to 0x46, 0x91 to 0x47, 0x13 to 0x48, 0x2D to 0x49, 0x24 to 0x4A, 0x21 to 0x4B,
                0x2E to 0x4C, 0x23 to 0x4D, 0x22 to 0x4E, 0x27 to 0x4F, 0x25 to 0x50, 0x28 to 0x51, 0x26 to 0x52,
                0x90 to 0x53, 0x6F to 0x54, 0x6A to 0x55, 0x6D to 0x56, 0x6B to 0x57, 0x60 to 0x62, 0x6E to 0x63,
                0x5D to 0x65,
                0x15 to 0x90, 0x19 to 0x91,          // 한/영(LANG1), 한자(LANG2)
            ))
        }
        private val MODS = mapOf(
            0x10 to 0x02, 0xA0 to 0x02, 0xA1 to 0x20,      // Shift
            0x11 to 0x01, 0xA2 to 0x01, 0xA3 to 0x10,      // Ctrl
            0x12 to 0x04, 0xA4 to 0x04, 0xA5 to 0x40,      // Alt
            0x5B to 0x08, 0x5C to 0x80,                    // Win
        )
        private val CONSUMER = mapOf(0xAD to 0xE2, 0xAE to 0xEA, 0xAF to 0xE9, 0xB3 to 0xCD, 0xB0 to 0xB5, 0xB1 to 0xB6)
    }

    @Volatile var connected = false
        private set
    @Volatile private var token: String? = null
    @Volatile private var running = true
    private val io = Executors.newSingleThreadExecutor()
    private var out: OutputStream? = null
    private var sock: Socket? = null

    // 상태 (전송 스레드에서만 사용)
    private var mods = 0
    private val keys = LinkedHashSet<Int>()
    private var buttons = 0
    private var lastX = 0f; private var lastY = 0f
    private var keyLogs = 0

    /** 서버에서 토큰을 받으면 연결 시작 (실패하면 3초마다 다시 시도) */
    fun setToken(t: String) {
        if (t == token && connected) return
        token = t
        io.execute { connect() }
    }

    private fun connect() {
        if (!running) return
        close(false)
        val t = token ?: return
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", UhidHelper.PORT), 1500)
            s.soTimeout = 2000
            s.getOutputStream().write("$t\n".toByteArray())
            val reply = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
            if (reply != "OK") { s.close(); throw Exception("토큰 불일치") }
            s.soTimeout = 0
            sock = s; out = s.getOutputStream()
            if (!connected) { connected = true; log("진짜 마우스 모드 켜짐"); onState(true) }
        } catch (e: Exception) {
            if (connected) { connected = false; onState(false) }
            // 도우미가 아직 준비 중일 수 있음 → 잠시 후 다시
            Thread { Thread.sleep(3000); io.execute { if (!connected) connect() } }.start()
        }
    }

    private fun close(report: Boolean) {
        try { sock?.close() } catch (_: Exception) {}
        sock = null; out = null
        if (report && connected) { connected = false; log("진짜 마우스 모드 끊김"); onState(false) }
    }

    private fun write(vararg report: Int) {
        val o = out ?: return
        try {
            val b = ByteArray(report.size + 1)
            b[0] = report.size.toByte()
            report.forEachIndexed { i, v -> b[i + 1] = v.toByte() }
            o.write(b); o.flush()
        } catch (e: Exception) {
            close(true)
            io.execute { connect() }
        }
    }

    // ------------------------------------------------------------ 마우스
    private fun mouseReport(dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) {
        write(2, buttons, dx and 0xFF, (dx shr 8) and 0xFF, dy and 0xFF, (dy shr 8) and 0xFF, wheel and 0xFF, pan and 0xFF)
    }

    private fun moveBy(dx: Int, dy: Int) {
        var x = dx; var y = dy
        do {
            val sx = x.coerceIn(-32767, 32767); val sy = y.coerceIn(-32767, 32767)
            mouseReport(sx, sy); x -= sx; y -= sy
        } while (x != 0 || y != 0)
    }

    /** 이 기기로 넘어올 때: 커서를 왼쪽 위 구석에 붙였다가 들어온 위치로 옮겨 위치를 맞춤 */
    fun enter(x: Float, y: Float) = io.execute {
        moveBy(-30000, -30000)
        lastX = 0f; lastY = 0f
        var tx = x.toInt(); var ty = y.toInt()
        while (tx != 0 || ty != 0) {           // 한 번에 크게 움직이면 가속이 붙어서 조금씩 나눠 이동
            val sx = tx.coerceIn(-60, 60); val sy = ty.coerceIn(-60, 60)
            moveBy(sx, sy); tx -= sx; ty -= sy
        }
        lastX = x; lastY = y
    }

    private var accX = 0f; private var accY = 0f

    /** 움직인 양 그대로 (화면 끝 제한 없음) */
    fun moveRel(dx: Float, dy: Float) = io.execute {
        accX += dx; accY += dy
        val ix = accX.toInt(); val iy = accY.toInt()
        if (ix != 0 || iy != 0) { accX -= ix; accY -= iy; moveBy(ix, iy) }
    }

    fun moveTo(x: Float, y: Float) = io.execute {
        val dx = (x - lastX).toInt(); val dy = (y - lastY).toInt()
        if (dx != 0 || dy != 0) { moveBy(dx, dy); lastX += dx; lastY += dy }
    }

    fun button(b: String, down: Boolean) = io.execute {
        val bit = when (b) { "l" -> 1; "r" -> 2; "m" -> 4; "x1" -> 8; "x2" -> 16; else -> 0 }
        if (bit == 0) return@execute
        buttons = if (down) buttons or bit else buttons and bit.inv()
        mouseReport(0, 0)
    }

    fun wheel(dx: Int, dy: Int) = io.execute {
        val v = (dy / 120f).let { if (it > 0) kotlin.math.ceil(it) else kotlin.math.floor(it) }.toInt()
        val h = (dx / 120f).let { if (it > 0) kotlin.math.ceil(it) else kotlin.math.floor(it) }.toInt()
        if (v != 0 || h != 0) mouseReport(0, 0, v.coerceIn(-127, 127), h.coerceIn(-127, 127))
    }

    // ------------------------------------------------------------ 키보드
    /** 처리했으면 true (모르는 키는 false) */
    fun key(vk: Int, down: Boolean): Boolean {
        MODS[vk]?.let { bit ->
            io.execute { mods = if (down) mods or bit else mods and bit.inv(); keyboardReport() }
            return true
        }
        CONSUMER[vk]?.let { usage ->
            if (down) io.execute { write(3, usage and 0xFF, usage shr 8); write(3, 0, 0) }
            return true
        }
        val code = VK_TO_HID[vk] ?: return false
        io.execute {
            if (down) keys.add(code) else keys.remove(code)
            keyboardReport()
            if (down && keyLogs < 3) { keyLogs++; log("키 전송 vk=$vk → HID $code (${if (out != null) "연결됨" else "연결 없음"})") }
        }
        return true
    }

    private fun keyboardReport() {
        val k = keys.take(6)
        write(1, mods, 0, k.getOrElse(0) { 0 }, k.getOrElse(1) { 0 }, k.getOrElse(2) { 0 },
            k.getOrElse(3) { 0 }, k.getOrElse(4) { 0 }, k.getOrElse(5) { 0 })
    }

    /** Win+Esc를 한 번에 '눌렀다 뗌'으로 보냄 (진짜 Esc로 들어가게).
     *  Win을 길게 잡고 있지 않으므로, 매크로가 중간에 멈추거나 떼는 신호가 늦어도
     *  'Win만 눌렀다 뗌'으로 처리돼 앱 서랍·작업 표시줄이 열리는 일이 없음 */
    fun tapMetaEsc() = io.execute {
        val esc = VK_TO_HID[0x1B] ?: return@execute
        val saved = mods
        mods = mods or 0x08; keyboardReport()                 // Win 누름
        keys.add(esc); keyboardReport()                       // + Esc 누름
        try { Thread.sleep(25) } catch (_: InterruptedException) {}
        keys.remove(esc); keyboardReport()                    // Esc 뗌 (Win은 아직 눌린 채)
        try { Thread.sleep(10) } catch (_: InterruptedException) {}
        mods = saved; keyboardReport()                        // Win 원래대로 (사용자가 Win을 잡고 있었으면 그대로)
    }

    fun releaseAll() = io.execute {
        keys.clear(); mods = 0; buttons = 0
        keyboardReport(); mouseReport(0, 0)
    }

    fun stop() {
        running = false
        io.execute { close(true) }
        io.shutdown()
    }
}

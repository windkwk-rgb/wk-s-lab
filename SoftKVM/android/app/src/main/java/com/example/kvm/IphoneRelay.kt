package com.example.kvm

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 아이폰 중계: 이 태블릿이 아이폰에 '블루투스 마우스+키보드'로 연결되고,
 * 메인 PC에는 'iphone'이라는 별도 기기로 접속해서 받은 입력을 블루투스로 전달합니다.
 * (아이폰: 설정 → 손쉬운 사용 → 터치 → AssistiveTouch 를 켜야 커서가 보임)
 */
@TargetApi(28)
@SuppressLint("MissingPermission")
class IphoneRelay(private val ctx: Context, private val log: (String) -> Unit = {}, private val onChange: () -> Unit) {

    companion object {
        // 키보드(1) + 마우스(2) + 미디어/홈 키(3) HID 보고서 형식
        private val DESCRIPTOR = byteArrayOf(
            // 키보드
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte(),
            // 마우스
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
            0x09, 0x01, 0xA1.toByte(), 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
            0xC0.toByte(), 0xC0.toByte(),
            // 미디어·홈
            0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x03,
            0x15, 0x00, 0x26, 0xFF.toByte(), 0x03, 0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03,
            0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00,
            0xC0.toByte(),
        )

        // 서버에 알리는 아이폰 가상 화면 크기와, 그 픽셀을 아이폰 포인터 이동량으로 바꾸는 비율
        private const val VW = 1179
        private const val VH = 2556
        private const val SCALE = 3f

        private val VK_TO_HID: Map<Int, Int> = HashMap<Int, Int>().apply {
            for (i in 0..25) put(0x41 + i, 0x04 + i)                 // A-Z
            for (i in 1..9) put(0x30 + i, 0x1E + i - 1)              // 1-9
            put(0x30, 0x27)                                          // 0
            for (i in 0..11) put(0x70 + i, 0x3A + i)                 // F1-F12
            for (i in 1..9) put(0x60 + i, 0x59 + i - 1)              // 키패드 1-9
            putAll(mapOf(
                0x0D to 0x28, 0x1B to 0x29, 0x08 to 0x2A, 0x09 to 0x2B, 0x20 to 0x2C,
                0xBD to 0x2D, 0xBB to 0x2E, 0xDB to 0x2F, 0xDD to 0x30, 0xDC to 0x31,
                0xBA to 0x33, 0xDE to 0x34, 0xC0 to 0x35, 0xBC to 0x36, 0xBE to 0x37, 0xBF to 0x38,
                0x14 to 0x39, 0x2C to 0x46, 0x13 to 0x48, 0x2D to 0x49, 0x24 to 0x4A, 0x21 to 0x4B,
                0x2E to 0x4C, 0x23 to 0x4D, 0x22 to 0x4E, 0x27 to 0x4F, 0x25 to 0x50, 0x28 to 0x51, 0x26 to 0x52,
                0x6F to 0x54, 0x6A to 0x55, 0x6D to 0x56, 0x6B to 0x57, 0x60 to 0x62, 0x6E to 0x63,
            ))
        }
        private const val GUI = 0x08; private const val SHIFT = 0x02; private const val ALT = 0x04; private const val CTRL = 0x01
        private val CONSUMER = mapOf(0xAD to 0xE2, 0xAE to 0xEA, 0xAF to 0xE9, 0xB3 to 0xCD, 0xB0 to 0xB5, 0xB1 to 0xB6)
        private const val HOME = 0x0223
    }

    // ------------------------------------------------------------ 상태 (화면 표시용)
    @Volatile var btStatus = "꺼짐"
    @Volatile var netStatus = ""
    @Volatile var hostName: String? = null

    private val prefs = Prefs(ctx)
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var net: NetClient? = null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@IphoneRelay.registered = registered
            if (!registered) { set("블루투스 등록이 해제됐어요"); return }
            set("아이폰 연결을 기다리는 중")
            startNet()     // 아이폰이 아직 안 붙어도 메인 PC 배치 창에 아이폰 상자가 보이게
            // 예전에 연결했던 아이폰이 있으면 먼저 다시 연결 시도
            val dev = pluggedDevice ?: prefs.iphoneAddr.takeIf { it.isNotBlank() }?.let {
                try { adapter?.getRemoteDevice(it) } catch (_: Exception) { null }
            }
            dev?.let { hid?.connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            log("블루투스 상태 ${device.address} → $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    hostName = device.name ?: "iPhone"
                    prefs.iphoneAddr = device.address
                    set("연결됨 · ${hostName}")
                    startNet()
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                    host = null; hostName = null
                    releaseAll()
                    set("아이폰 연결을 기다리는 중")
                }
            }
        }
    }

    private fun set(s: String) { btStatus = s; log(s); onChange() }

    @Volatile private var registered = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun start(): Boolean {
        val a = adapter ?: run { set("이 기기에는 블루투스가 없어요"); return false }
        if (!a.isEnabled) { set("블루투스를 켜 주세요"); return false }
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            set("'근처 기기' 권한이 필요해요 · 앱에서 아이폰 연결을 다시 켜 주세요"); return false
        }
        set("블루투스 준비 중")
        requestProxy(a, 1)
        return true
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            log("블루투스 키보드 기능 응답함")
            hid = proxy as BluetoothHidDevice
            register()
        }
        override fun onServiceDisconnected(profile: Int) { hid = null; registered = false; set("블루투스 서비스가 끊겼어요") }
    }

    /** 앱 전체 문맥으로 요청하고, 응답이 없으면 한 번 더 시도 */
    private fun requestProxy(a: android.bluetooth.BluetoothAdapter, attempt: Int) {
        val ok = try { a.getProfileProxy(ctx.applicationContext, listener, BluetoothProfile.HID_DEVICE) }
                 catch (e: Exception) { log("요청 오류: $e"); false }
        log("블루투스 키보드 기능 요청 ($attempt): $ok · 블루투스 상태 ${a.state}")
        if (!ok) { set("이 기기는 블루투스 키보드·마우스 기능을 지원하지 않아요"); return }
        main.postDelayed({
            if (hid != null) return@postDelayed
            if (attempt < 2) requestProxy(a, attempt + 1)
            else startBle()
        }, 4000)
    }

    // ------------------------------------------------------------ BLE 방식 (일반 방식이 막힌 기기용)
    private var ble: BleHid? = null

    private fun startBle() {
        if (ble != null) return
        log("일반 블루투스 키보드 기능이 꺼져 있어 BLE 방식으로 전환")
        ble = BleHid(ctx, DESCRIPTOR, { prefs.iphoneBleAddr }, log) { dev -> main.post { onBleConnected(dev) } }
        if (ble?.start() == true) {
            set("아이폰 연결을 기다리는 중 (BLE)")
            startNet()
        } else {
            set("이 기기는 BLE 키보드·마우스도 지원하지 않아요")
        }
    }

    private fun onBleConnected(dev: BluetoothDevice?) {
        if (dev != null) {
            hostName = dev.name ?: "iPhone"
            prefs.iphoneBleAddr = dev.address
            set("연결됨 · $hostName (BLE)")
            startNet()
        } else {
            hostName = null
            releaseAll()
            set("아이폰 연결을 기다리는 중 (BLE)")
        }
    }

    private var registerTries = 0

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings("PC 마우스 공유", "KVM 키보드·마우스", "KVM",
            BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR)
        val ok = try { h.registerApp(sdp, null, null, exec, callback) } catch (e: Exception) { log("등록 오류: $e"); false }
        log("블루투스 키보드 등록 요청: $ok")
        if (!ok) {
            set("등록 실패 · 다른 블루투스 키보드 앱(appground 등)을 지우고 다시 켜 주세요")
            return
        }
        // 등록 결과가 안 오면 한 번 풀었다가 다시 등록
        main.postDelayed({
            if (!registered && hid != null && registerTries < 2) {
                registerTries++
                log("등록 응답 없음 → 다시 시도 ($registerTries)")
                try { hid?.unregisterApp() } catch (_: Exception) {}
                main.postDelayed({ register() }, 800)
            } else if (!registered) {
                set("등록 응답 없음 · 다른 블루투스 키보드 앱(appground 등)을 지우고 태블릿을 재시작해 주세요")
            }
        }, 4000)
    }

    fun stop() {
        stopNet()
        ble?.stop(); ble = null
        try { hid?.let { it.unregisterApp(); adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } } catch (_: Exception) {}
        hid = null; host = null; hostName = null
        set("꺼짐")
    }

    // ------------------------------------------------------------ 메인 PC 연결 (아이폰 몫)
    private fun startNet() {
        if (net != null || prefs.host.isBlank()) return
        net = NetClient(prefs.host, prefs.port, prefs.iphoneName, prefs.key,
            sizeProvider = { size() },
            onMessage = ::onMessage,
            onStatus = { netStatus = it; onChange() },
            platform = "iphone").also { it.start() }
    }

    /** 아이폰 가상 화면 크기 (가로로 쓰면 가로·세로를 바꿈) */
    private fun size(): Pair<Int, Int> = if (prefs.iphoneLandscape) Pair(VH, VW) else Pair(VW, VH)

    /** 가로/세로를 바꾸면 메인 PC 배치 창의 상자 모양도 바로 바뀜 */
    fun setLandscape(on: Boolean) {
        prefs.iphoneLandscape = on
        val (w, h) = size()
        net?.send(JSONObject().put("t", "size").put("w", w).put("h", h))
        onChange()
    }

    private fun stopNet() {
        net?.stop(); net = null
        netStatus = ""
        releaseAll()
    }

    fun reconnectNet() { if (net != null) { stopNet(); startNet() } }

    // ------------------------------------------------------------ 입력 변환
    private var lastX = 0f; private var lastY = 0f
    private var accX = 0f; private var accY = 0f
    private var buttons = 0
    private val held = LinkedHashSet<Int>()       // 눌린 일반 키 (HID 코드)
    private var shift = false; private var ctrl = false; private var alt = false
    private var win = false; private var winUsed = false; private var altTab = false

    private fun send(id: Int, data: ByteArray) {
        ble?.let { if (it.connected) { it.send(id, data); return } }
        val h = hid ?: return; val d = host ?: return
        try { h.sendReport(d, id, data) } catch (_: Exception) {}
    }

    private fun mouse(dx: Int, dy: Int, wheel: Int = 0) {
        var x = dx; var y = dy; var w = wheel
        do {
            val sx = x.coerceIn(-127, 127); val sy = y.coerceIn(-127, 127); val sw = w.coerceIn(-127, 127)
            send(2, byteArrayOf(buttons.toByte(), sx.toByte(), sy.toByte(), sw.toByte()))
            x -= sx; y -= sy; w -= sw
        } while (x != 0 || y != 0 || w != 0)
    }

    private fun mods(): Int {
        var m = 0
        if (shift) m = m or SHIFT
        if (ctrl || altTab) m = m or GUI          // 윈도우 Ctrl → 아이폰 Cmd (Ctrl+C/V 그대로)
        if (alt && !altTab) m = m or ALT
        return m
    }

    private fun keyboard(extraMods: Int = 0) {
        val keys = held.take(6)
        val r = ByteArray(8)
        r[0] = (mods() or extraMods).toByte()
        keys.forEachIndexed { i, k -> r[2 + i] = k.toByte() }
        send(1, r)
    }

    private fun tap(code: Int, modMask: Int) {
        send(1, byteArrayOf(modMask.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
        send(1, ByteArray(8))
    }

    private fun consumer(usage: Int) {
        send(3, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
        send(3, byteArrayOf(0, 0))
    }

    private fun releaseAll() {
        held.clear(); buttons = 0
        shift = false; ctrl = false; alt = false; win = false; altTab = false
        if (host != null || ble?.connected == true) { send(1, ByteArray(8)); send(2, ByteArray(4)) }
    }

    private fun onMessage(m: JSONObject) {
        try { handle(m) } catch (e: Exception) { log("입력 처리 오류: $e") }
    }

    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "enter" -> { lastX = m.optDouble("x").toFloat(); lastY = m.optDouble("y").toFloat(); accX = 0f; accY = 0f }
            "move" -> {
                val x = m.optDouble("x").toFloat(); val y = m.optDouble("y").toFloat()
                accX += (x - lastX) / SCALE; accY += (y - lastY) / SCALE
                lastX = x; lastY = y
                val dx = accX.toInt(); val dy = accY.toInt()
                if (dx != 0 || dy != 0) { accX -= dx; accY -= dy; mouse(dx, dy) }
            }
            "rmove" -> {
                accX += m.optDouble("dx").toFloat() / SCALE; accY += m.optDouble("dy").toFloat() / SCALE
                val dx = accX.toInt(); val dy = accY.toInt()
                if (dx != 0 || dy != 0) { accX -= dx; accY -= dy; mouse(dx, dy) }
            }
            "btn" -> {
                val bit = when (m.optString("b")) { "l" -> 1; "r" -> 2; "m" -> 4; else -> 0 }
                if (bit != 0) {
                    buttons = if (m.optBoolean("d")) buttons or bit else buttons and bit.inv()
                    mouse(0, 0)
                }
            }
            "wheel" -> {
                val notches = (m.optInt("dy") / 120f).roundToInt()
                if (notches != 0) mouse(0, 0, notches)
            }
            "key" -> onKey(m.optInt("vk"), m.optBoolean("d"))
            "leave" -> releaseAll()
        }
    }

    private fun onKey(vk: Int, down: Boolean) {
        when (vk) {
            0x10, 0xA0, 0xA1 -> { shift = down; keyboard(); return }
            0x11, 0xA2, 0xA3 -> { ctrl = down; keyboard(); return }
            0x12, 0xA4, 0xA5 -> { alt = down; if (!down) altTab = false; keyboard(); return }
            0x5B, 0x5C -> {
                if (down) { win = true; winUsed = false }
                else { win = false; if (!winUsed) consumer(HOME) }   // Win 단독 → 홈 화면
                return
            }
            0x15 -> { if (down) tap(0x2C, CTRL); return }            // 한/영 → Ctrl+Space (아이폰 언어 전환)
        }
        if (down) {
            CONSUMER[vk]?.let { consumer(it); return }               // 볼륨·재생 키
            if (win) {
                winUsed = true
                when (vk) {
                    0x44 -> consumer(HOME)                           // Win+D 홈
                    0x20, 0x53 -> tap(0x2C, GUI)                     // Win+Space/S → Spotlight 검색
                    0x09 -> tap(0x2B, GUI)                           // Win+Tab → 앱 전환
                }
                return
            }
            if (alt && vk == 0x09) altTab = true                     // Alt+Tab → Cmd+Tab
            if (vk == 0x2C) { tap(0x20, GUI or SHIFT); return }      // PrtSc → 스크린샷 (Cmd+Shift+3)
        }
        val code = VK_TO_HID[vk] ?: return
        if (down) held.add(code) else held.remove(code)
        keyboard()
    }
}

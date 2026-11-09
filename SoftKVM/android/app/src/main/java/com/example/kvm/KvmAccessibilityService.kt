package com.example.kvm

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 마우스 담당.
 *  - 화면 위에 커서를 그림 (접근성 오버레이: 별도 권한 불필요)
 *  - 왼쪽 클릭 → 탭 / 누른 채 이동 → 드래그 / 오래 누르기 → 길게 누르기
 *  - 오른쪽 클릭·뒤로 버튼 → 뒤로가기, 가운데 클릭 → 홈, 앞으로 버튼 → 최근 앱
 *  - 휠 → 스와이프 스크롤
 *  - 키 입력은 KvmIme(키보드 앱)로 전달
 */
class KvmAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: KvmAccessibilityService? = null
        @Volatile var lastStatus: String = "꺼짐"
        var statusListener: ((String) -> Unit)? = null

        // 연결 화면에 보여줄 실시간 정보
        @Volatile var serverName: String = ""
        @Volatile var side: String = ""
        @Volatile var peers: List<String> = emptyList()
        @Volatile var active: Boolean = false
        @Volatile var connectedAt: Long = 0L
        @Volatile var hidOn: Boolean = false      // 진짜 마우스 모드 (셸 도우미 연결됨)
        @Volatile var macroState: JSONObject? = null   // 메인 PC가 보내 준 매크로 상태 (이 기기에서 녹화한 것만)
        var liveListener: (() -> Unit)? = null
        var iphoneListener: (() -> Unit)? = null

        // 자동 업데이트: 서버에 이 앱보다 새 버전이 있으면 이름이 들어감
        @Volatile var updateName: String? = null
        var updateListener: (() -> Unit)? = null
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var net: NetClient? = null

    @Volatile private var screenW = 1080
    @Volatile private var screenH = 2400

    private var cursor: View? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var cx = 0f
    private var cy = 0f

    private var leftDown = false
    private var leftDownAt = 0L
    private val dragPath = ArrayList<PointF>()

    private var pendingDx = 0
    private var pendingDy = 0
    private var wheelScheduled = false

    private var lastClip: String? = null

    // ------------------------------------------------------------ 생명주기
    /** 메인 PC 로그(kvm-server.log)에 태블릿 기록을 남김 (문제 확인용) */
    // ------------------------------------------------------------ 게임 화면 위 매크로 이름 표시 (터치는 통과)
    private var macroBadge: android.widget.TextView? = null

    private fun updateMacroBadge(m: JSONObject?) {
        val (text, kind) = badgeText(m)
        if (text.isEmpty()) { macroBadge?.visibility = View.GONE; return }
        val v = macroBadge ?: android.widget.TextView(this).also { tv ->
            val d = resources.displayMetrics.density
            tv.textSize = 13f
            tv.setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tv.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC14161C.toInt()); cornerRadius = 10 * d
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; x = (16 * d).toInt(); y = (10 * d).toInt() }
            try { wm.addView(tv, lp) } catch (_: Exception) { return }
            macroBadge = tv
        }
        // 위치: tl 좌상단 · ml 좌중단 · bl 좌하단 · tr 우상단 · mr 우중단 · br 우하단
        val pos = m?.optString("overlay_pos", "tl") ?: "tl"
        (v.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
            val g = (if (pos.endsWith("r")) Gravity.END else if (pos.endsWith("c")) Gravity.CENTER_HORIZONTAL else Gravity.START) or
                (when (pos.firstOrNull()) { 'm' -> Gravity.CENTER_VERTICAL; 'b' -> Gravity.BOTTOM; else -> Gravity.TOP })
            if (lp.gravity != g) {
                lp.gravity = g
                val d = resources.displayMetrics.density
                lp.x = if (pos.endsWith("c")) 0 else (16 * d).toInt()
                lp.y = if (pos.startsWith("m")) 0 else (10 * d).toInt()
                try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
            }
        }
        v.text = text
        v.setTextColor(when (kind) { "rec" -> 0xFFFF7B7B.toInt(); "play" -> 0xFF5FE3A8.toInt(); else -> 0xFFC9D1DC.toInt() })
        v.visibility = View.VISIBLE
    }

    // 이 태블릿 배터리 표시 (이 화면에만 · 켜기와 위치는 앱의 설정 탭에서)
    private var batBadge: android.widget.TextView? = null
    private val batTick = object : Runnable {
        override fun run() { batAt = 0L; updateBatteryBadge(); main.postDelayed(this, 30_000) }
    }

    fun updateBatteryBadge() {
        val p = Prefs(this)
        val b = battery()
        val connected = lastStatus.startsWith("연결됨")
        if (!p.batteryOverlay || b == null || !connected) { batBadge?.visibility = View.GONE; return }
        val (lv, chg) = b
        val text = "🔋  $lv%${if (chg) " ⚡" else ""}"
        val mPos = macroState?.let { ms -> if (badgeText(ms).first.isNotEmpty()) ms.optString("overlay_pos", "tl") else null }
        val d = resources.displayMetrics.density
        val v = batBadge ?: android.widget.TextView(this).also { tv ->
            tv.textSize = 13f
            tv.setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tv.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC14161C.toInt()); cornerRadius = 10 * d }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.END; x = (16 * d).toInt(); y = (10 * d).toInt() }
            try { wm.addView(tv, lp) } catch (_: Exception) { return }
            batBadge = tv
        }
        val pos = p.batteryPos
        val off = if (mPos == pos) (36 * d).toInt() else 0          // 매크로 표시와 같은 자리면 아래에
        (v.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
            lp.gravity = (if (pos.endsWith("r")) Gravity.END else if (pos.endsWith("c")) Gravity.CENTER_HORIZONTAL else Gravity.START) or
                (when (pos.firstOrNull()) { 'm' -> Gravity.CENTER_VERTICAL; 'b' -> Gravity.BOTTOM; else -> Gravity.TOP })
            lp.x = if (pos.endsWith("c")) 0 else (16 * d).toInt()
            lp.y = (if (pos.startsWith("m")) 0 else (10 * d).toInt()) + off
            try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
        }
        v.text = text
        v.setTextColor(if (lv <= 20 && !chg) 0xFFFF7B7B.toInt() else 0xFFC9D1DC.toInt())
        v.visibility = View.VISIBLE
    }

    private fun badgeText(m: JSONObject?): Pair<String, String> {
        if (m == null || !m.optBoolean("overlay", true)) return "" to "idle"
        val dev = m.optString("dev")
        val name = if (m.isNull("name")) (if (m.optInt("count") > 0) "새 녹화" else "") else m.optString("name")
        val slot = if (m.isNull("cur_slot")) "" else m.optString("cur_slot")
        if (m.optBoolean("recording") && m.optString("rec_dev") == dev) return "●  매크로 녹화 중" to "rec"
        if (m.optBoolean("playing") && m.optString("play_dev") == dev) {
            val rnd = if (m.optInt("repeat", 1) != 1) " (${m.optInt("round")}번째)" else ""
            return "▶  ${name.ifEmpty { "매크로" }} 실행 중$rnd" to "play"
        }
        val off = m.has("keys_on") && !m.optBoolean("keys_on", true)
        if (name.isEmpty()) return (if (off) "매크로 키 꺼짐" else "") to "idle"
        return "매크로  ${if (slot.isNotEmpty()) "[$slot] " else ""}$name${if (off) "  · 키 꺼짐" else ""}" to "idle"
    }

    // 매크로 보관 파일 (이 태블릿에서 녹화한 것만)
    private val macroFile get() = java.io.File(filesDir, "macros.json")

    private fun loadMacros(): JSONObject = try {
        if (macroFile.exists()) JSONObject(macroFile.readText()) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    private fun saveMacros(data: JSONObject) {
        try {
            val tmp = java.io.File(filesDir, "macros.json.tmp")
            tmp.writeText(data.toString())
            tmp.renameTo(macroFile)
        } catch (e: Exception) { sendLog("매크로 저장 실패: ${e.message}") }
    }

    /** 매크로 명령을 메인 PC로 (녹화·실행·저장·불러오기 등) */
    fun sendMacro(op: String, extra: JSONObject = JSONObject()): Boolean {
        val n = net ?: return false
        extra.put("t", "macro").put("op", op)
        n.send(extra)
        return true
    }

    fun sendLog(msg: String) {
        net?.send(JSONObject().put("t", "log").put("msg", msg))
    }

    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { java.io.File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e).take(3000)) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun sendPendingCrash() {
        val f = java.io.File(filesDir, "crash.txt")
        if (f.exists()) { sendLog("앱 오류로 다시 시작됨:\n" + f.readText()); f.delete() }
    }

    // ------------------------------------------------------------ 진짜 마우스 모드
    private val hid = UhidLink(log = { m -> main.post { sendLog(m) } }) { on ->
        main.post {
            hidOn = on
            if (on) cursor?.visibility = View.GONE        // 안드로이드가 자기 마우스 커서를 그림
            else if (active) cursor?.visibility = View.VISIBLE
            net?.send(JSONObject().put("t", "hid_state").put("on", on))
            liveListener?.invoke()
        }
    }

    override fun onServiceConnected() {
        installCrashLog()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateSize()
        addCursor()
        connect()
        if (Prefs(this).iphoneEnabled) setIphone(true)
    }

    // ------------------------------------------------------------ 아이폰 중계
    var iphone: IphoneRelay? = null
        private set

    /** 켜면 이 태블릿이 아이폰용 블루투스 키보드·마우스가 됨 */
    fun setIphone(on: Boolean) {
        Prefs(this).iphoneEnabled = on
        if (on) {
            if (Build.VERSION.SDK_INT < 28) return
            if (iphone == null) iphone = IphoneRelay(this, log = { m -> main.post { sendLog("아이폰 중계: $m") } }) {
                main.post { iphoneListener?.invoke() }
            }
            try { iphone?.start() } catch (e: Exception) { sendLog("아이폰 중계 시작 오류: $e") }
        } else {
            iphone?.stop(); iphone = null
        }
        iphoneListener?.invoke()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(batTick)
        batBadge?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        hid.stop(); hidOn = false
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        awakeView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        awakeView = null
        iphone?.stop(); iphone = null
        instance = null
        net?.stop()
        net = null
        cursor?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        setStatus("꺼짐")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val ow = screenW
        val oh = screenH
        updateSize()
        if (ow != screenW || oh != screenH) {
            net?.send(JSONObject().put("t", "size").put("w", screenW).put("h", screenH))
        }
    }

    fun reconnect() {
        rejectReason = null
        iphone?.reconnectNet()
        net?.stop()
        net = null
        connect()
    }

    private fun connect() {
        val p = Prefs(this)
        if (p.host.isBlank()) {
            setStatus("서버 IP를 앱에서 입력하세요")
            return
        }
        net = NetClient(
            p.host, p.port, p.name, p.key,
            sizeProvider = { Pair(screenW, screenH) },
            onMessage = ::onMessage,
            onStatus = ::setStatus,
            appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" },
            batteryProvider = ::battery,
        ).also { it.start() }
    }

    private var rejectReason: String? = null

    // 배터리 잔량 (메인 PC 기기 목록에 표시) · 30초마다 한 번만 읽음
    private var batCache: Pair<Int, Boolean>? = null
    private var batAt = 0L
    private var batWarnAt = 0L
    private fun battery(): Pair<Int, Boolean>? {
        val now = SystemClock.uptimeMillis()
        if (batCache != null && now - batAt < 30_000) return batCache
        batAt = now
        batCache = try {
            // 1순위: BatteryManager 잔량(%) — 기기가 직접 계산한 값이라 정확
            val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val cap = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
            val i = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val st = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val fromIntent = if (level < 0) -1 else level * 100 / maxOf(scale, 1)
            val lv = if (cap in 0..100) cap else fromIntent
            // 두 값이 크게 다르면 메인 PC 기록에 남김 (원인 확인용 · 1분에 한 번만)
            if (cap in 0..100 && fromIntent >= 0 && kotlin.math.abs(cap - fromIntent) >= 5 && now - batWarnAt > 60_000) {
                batWarnAt = now
                sendLog("배터리 값 불일치: BatteryManager $cap% · 방송 $fromIntent% (level=$level scale=$scale)")
            }
            val chg = (bm?.isCharging == true) || st == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                st == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (lv !in 0..100) null else Pair(lv, chg)
        } catch (_: Exception) { null }
        return batCache
    }

    private fun setStatus(s: String) {
        // 서버가 거부 사유를 보낸 직후의 '끊김'은 사유를 그대로 보여줌
        if (s.startsWith("서버 연결 끊김") && rejectReason != null) return
        if (s.startsWith("연결됨")) {
            rejectReason = null; connectedAt = System.currentTimeMillis()
            main.removeCallbacks(batTick); main.postDelayed(batTick, 1500)
        }
        else { active = false; macroState = null; main.post { updateMacroBadge(null); updateBatteryBadge() } }   // 끊기면 표시 숨김
        lastStatus = s
        updateKeepAwake()
        updateWifiLock()
        statusListener?.invoke(s)
    }

    private fun updateSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
    }

    // ------------------------------------------------------------ 화면 켜두기·깨우기
    private var awakeView: View? = null

    /** 메인 PC와 연결된 동안 화면이 꺼지지 않게 (보이지 않는 1픽셀 창에 '화면 켜둠' 표시) */
    fun updateKeepAwake() {
        val want = lastStatus.startsWith("연결됨") && Prefs(this).keepAwake
        if (want && awakeView == null) {
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try { wm.addView(v, lp); awakeView = v } catch (_: Exception) {}
        } else if (!want && awakeView != null) {
            try { wm.removeView(awakeView) } catch (_: Exception) {}
            awakeView = null
        }
    }

    // 와이파이 절전 끄기: 안드로이드는 와이파이를 절전 모드로 두면 신호를 모아서 받기 때문에
    // 마우스가 뚝뚝 끊기거나 100ms 이상 늦어짐 → 연결된 동안은 '저지연 모드'로 유지
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun updateWifiLock() {
        val want = lastStatus.startsWith("연결됨")
        try {
            if (want && wifiLock == null) {
                val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                           else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "kvm:lowlatency").apply { setReferenceCounted(false); acquire() }
            } else if (!want && wifiLock != null) {
                wifiLock?.release(); wifiLock = null
            }
        } catch (e: Exception) { sendLog("와이파이 저지연 모드 오류: $e") }
    }

    /** 화면이 꺼져 있으면 켬 (마우스가 이 기기로 넘어왔을 때) */
    private fun wakeScreen() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isInteractive) return
        try {
            startActivity(android.content.Intent(this, WakeActivity::class.java).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) { sendLog("화면 깨우기 실패: $e") }
    }

    // ------------------------------------------------------------ 커서 오버레이
    private fun addCursor() {
        val size = (resources.displayMetrics.density * 26).toInt()
        val view = CursorView(this).apply { visibility = View.GONE }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(view, lp)
        cursor = view
        cursorParams = lp
    }

    private fun moveCursor(x: Float, y: Float) {
        cx = x.coerceIn(0f, (screenW - 1).toFloat())
        cy = y.coerceIn(0f, (screenH - 1).toFloat())
        val v = cursor ?: return
        val lp = cursorParams ?: return
        lp.x = cx.toInt(); lp.y = cy.toInt()
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
        if (leftDown) {
            val last = dragPath.lastOrNull()
            if (last == null || hypot(last.x - cx, last.y - cy) > 6f) dragPath.add(PointF(cx, cy))
        }
    }

    // ------------------------------------------------------------ 서버 메시지
    private fun onMessage(m: JSONObject) {
        when (m.optString("t")) {
            "uhid_token" -> hid.setToken(m.optString("token"))
            "welcome" -> {
                net?.send(JSONObject().put("t", "hid_state").put("on", hidOn))
                serverName = m.optString("server"); side = m.optString("side"); liveListener?.invoke()
                sendPendingCrash()
                iphone?.let { sendLog("아이폰 중계: ${it.btStatus}") }
                // 이 태블릿에 보관 중인 매크로를 메인 PC로 (실행은 메인 PC가, 보관은 이 태블릿이)
                net?.send(JSONObject().put("t", "macro_lib").put("data", loadMacros()))
                net?.send(JSONObject().put("t", "macro").put("op", "state"))
            }
            "macro_store" -> saveMacros(m.optJSONObject("data") ?: JSONObject())
            "rename" -> {        // 메인 PC 설정 창에서 이 태블릿 이름을 바꿈
                val n = m.optString("name")
                if (n.isNotEmpty()) { Prefs(this).name = n; sendLog("이름 → $n"); main.post { reconnect() } }
            }
            "macro_state" -> { macroState = m; liveListener?.invoke(); main.post { updateMacroBadge(m); updateBatteryBadge() } }
            "layout" -> { side = m.optString("side"); liveListener?.invoke() }
            "peers" -> {
                val a = m.optJSONArray("names")
                peers = if (a == null) emptyList() else List(a.length()) { a.optString(it) }
                liveListener?.invoke()
            }
            "enter" -> {
                active = true; liveListener?.invoke()
                wakeScreen()
                if (hidOn) {
                    hid.enter(m.optDouble("x").toFloat(), m.optDouble("y").toFloat())
                } else {
                    cursor?.visibility = View.VISIBLE
                    moveCursor(m.optDouble("x").toFloat(), m.optDouble("y").toFloat())
                }
            }
            "leave" -> {
                active = false; liveListener?.invoke()
                keyToIme.clear()
                restoreUserIme()
                leftDown = false
                dragPath.clear()
                cursor?.visibility = View.GONE
                if (hidOn && !m.optBoolean("keep")) hid.releaseAll()     // 매크로 실행 중이면 그대로 둠
                KvmIme.instance?.resetModifiers()
                altDown = false; winDown = false; altTabCount = 0; swallowUp.clear()
                tapLogs = 0; keyRouteLogs = 0
                sendClipboardIfChanged()
            }
            "warp" -> {     // 매크로: 정해진 위치로 커서 맞추기
                val x = m.optDouble("x").toFloat(); val y = m.optDouble("y").toFloat()
                if (hidOn) hid.enter(x, y) else moveCursor(x, y)
            }
            "toast" -> {
                val text = m.optString("text")
                main.post { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show() }
            }
            "rmove" -> if (hidOn) hid.moveRel(m.optDouble("dx").toFloat(), m.optDouble("dy").toFloat())
            "move" -> if (hidOn) hid.moveTo(m.optDouble("x").toFloat(), m.optDouble("y").toFloat())
                      else moveCursor(m.optDouble("x").toFloat(), m.optDouble("y").toFloat())
            "btn" -> if (hidOn) hid.button(m.optString("b"), m.optBoolean("d")) else onButton(m.optString("b"), m.optBoolean("d"))
            "wheel" -> if (hidOn) hid.wheel(m.optInt("dx"), m.optInt("dy")) else onWheel(m.optInt("dx"), m.optInt("dy"))
            "key" -> onKey(m.optInt("vk"), m.optBoolean("d"))
            "clip" -> setClipboard(m.optString("text"))
            "apk_info" -> onApkInfo(m.optString("name"), m.optLong("code"))
            "apk_data" -> onApkData(m.optString("b64"))
            "apk_error" -> { apkCallback?.invoke(null); apkCallback = null }
            "error" -> {
                rejectReason = m.optString("msg")
                lastStatus = "서버 거부: $rejectReason"
                statusListener?.invoke(lastStatus)
            }
        }
    }

    private fun onButton(b: String, down: Boolean) {
        when (b) {
            "l" -> if (down) {
                leftDown = true
                leftDownAt = SystemClock.uptimeMillis()
                dragPath.clear()
                dragPath.add(PointF(cx, cy))
            } else if (leftDown) {
                leftDown = false
                dragPath.add(PointF(cx, cy))
                performPress(SystemClock.uptimeMillis() - leftDownAt)
            }
            "r", "x1" -> if (down) performGlobalAction(GLOBAL_ACTION_BACK)
            "m" -> if (down) performGlobalAction(GLOBAL_ACTION_HOME)
            "x2" -> if (down) performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    /** 누른 시간과 이동 경로를 그대로 터치 제스처로 재생 */
    private fun performPress(elapsed: Long) {
        val start = dragPath.first()
        val moved = dragPath.any { abs(it.x - start.x) > 15 || abs(it.y - start.y) > 15 }
        val path = Path().apply {
            moveTo(start.x, start.y)
            if (moved) dragPath.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val duration = elapsed.coerceIn(40L, 10_000L)
        val x = start.x; val y = start.y
        dispatch(path, duration) { ok ->
            if (tapLogs < 3) { tapLogs++; sendLog("${if (moved) "드래그" else "탭"} ($x, $y): ${if (ok) "완료" else "취소됨"}") }
            // 시스템이 터치 흉내를 막은 경우: 그 위치의 버튼을 직접 누름
            if (!ok && !moved) clickNodeAt(x.toInt(), y.toInt())
        }
        dragPath.clear()
    }

    /** 휠: 짧은 시간 동안 모아서 한 번의 스와이프로 */
    private fun onWheel(dx: Int, dy: Int) {
        pendingDx += dx
        pendingDy += dy
        if (wheelScheduled) return
        wheelScheduled = true
        main.postDelayed({
            wheelScheduled = false
            val stepPx = resources.displayMetrics.density * 90   // 휠 한 칸 이동 거리
            val ex = (cx - pendingDx / 120f * stepPx).coerceIn(1f, screenW - 2f)
            val ey = (cy + pendingDy / 120f * stepPx).coerceIn(1f, screenH - 2f)
            pendingDx = 0; pendingDy = 0
            if (abs(ex - cx) < 2 && abs(ey - cy) < 2) return@postDelayed
            dispatch(Path().apply { moveTo(cx, cy); lineTo(ex, ey) }, 150)
        }, 40)
    }

    private var tapLogs = 0
    private var keyRouteLogs = 0

    private fun dispatch(path: Path, duration: Long, done: ((Boolean) -> Unit)? = null) {
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val cb = if (done == null) null else object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done(true) }
            override fun onCancelled(g: GestureDescription?) { done(false) }
        }
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, null)
        if (!sent) done?.invoke(false)
    }

    /** 대체 클릭: 화면의 그 위치에 있는 '누를 수 있는' 항목을 찾아 직접 누름 */
    private fun clickNodeAt(x: Int, y: Int) {
        val r = android.graphics.Rect()
        fun find(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (n == null || !n.isVisibleToUser) return null
            n.getBoundsInScreen(r)
            if (!r.contains(x, y)) return null
            for (i in n.childCount - 1 downTo 0) find(n.getChild(i))?.let { return it }
            return if (n.isClickable || n.isLongClickable) n else null
        }
        val target = windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { w ->
            if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) null
            else { w.getBoundsInScreen(r); if (r.contains(x, y)) find(w.root) else null }
        } ?: findClickableParent(x, y)
        val ok = target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
        if (tapLogs < 6) { tapLogs++; sendLog("대체 클릭 ($x, $y): ${if (ok) "성공" else "누를 항목 없음"}") }
    }

    private fun findClickableParent(x: Int, y: Int): android.view.accessibility.AccessibilityNodeInfo? {
        val r = android.graphics.Rect()
        var n = rootInActiveWindow ?: return null
        while (true) {
            var next: android.view.accessibility.AccessibilityNodeInfo? = null
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                c.getBoundsInScreen(r)
                if (r.contains(x, y)) next = c
            }
            n = next ?: break
        }
        var p: android.view.accessibility.AccessibilityNodeInfo? = n
        while (p != null && !p.isClickable) p = p.parent
        return p
    }

    // ------------------------------------------------------------ 키보드 단축키 → 안드로이드 동작
    private var altDown = false
    private var winDown = false
    private var winUsed = false
    private var altTabCount = 0
    private val swallowUp = HashSet<Int>()   // 단축키로 쓴 키는 뗄 때도 앱에 안 보냄

    private fun onKey(vk: Int, down: Boolean) {
        when (vk) {
            0x12, 0xA4, 0xA5 -> {                          // Alt
                altDown = down
                if (!down) {
                    if (altTabCount == 1) quickSwitch()      // Alt+Tab 한 번 → 직전 앱으로
                    altTabCount = 0
                }
                KvmIme.instance?.onRemoteKey(vk, down)
                if (hidOn) hid.key(vk, down)
                return
            }
            0x5B, 0x5C -> {                                // Win 키
                if (down) { winDown = true; winUsed = false }
                else { winDown = false; if (!winUsed) performGlobalAction(GLOBAL_ACTION_HOME) }
                return
            }
        }
        if (!down && swallowUp.remove(vk)) return
        if (down && shortcut(vk)) { swallowUp.add(vk); return }
        val ime = KvmIme.instance
        // 한/영 키: 글자 입력칸에 있는데 'PC 키보드 공유'가 지금 키보드가 아니면 자동으로 바꿔서 한글 켜기
        if (vk == 0x15 && down) {
            val editable = focusedEditable()
            sendLog("한/영 키: 입력칸 ${if (ime?.hasTextField == true) "있음" else if (editable) "있음(화면)" else "없음"}, " +
                "한글 ${if (ime?.isHangul == true) "켜짐→꺼짐" else "꺼짐→켜짐"}, 키보드 앱 ${if (ime != null) "실행 중" else "꺼짐"}, 지금 키보드 ${currentImeId()}")
            if (ime == null && editable) {
                if (switchToKvmIme()) {
                    main.postDelayed({ KvmIme.instance?.setHangul(true) }, 500)
                    toast("한글 입력: 'PC 키보드 공유' 키보드로 바꿨어요")
                } else toast("한글을 쓰려면 'PC 키보드 공유'를 키보드로 선택해 주세요")
                return
            }
        }
        if (hidOn) {
            // 진짜 키보드 신호로 보냄 (게임 키 입력 가능). 한글 입력 중인 글자·한/영 키만 키보드 앱으로
            val modifier = vk in intArrayOf(0x10, 0xA0, 0xA1, 0x11, 0xA2, 0xA3)
            // 한글 모드이고 '글자 입력칸'에 있을 때만 키보드 앱으로 (게임 화면에서는 항상 진짜 키 입력)
            val letterish = vk in 0x41..0x5A || vk == 0x08 || vk == 0x20 || vk == 0x0D
            val hangulText = ime != null && ime.isHangul && letterish && (ime.hasTextField || editableCached())
            if (down && keyRouteLogs < 6) {
                keyRouteLogs++
                sendLog("키 vk=$vk → ${if ((vk == 0x15 && ime?.hasTextField == true) || hangulText) "키보드 앱" else "진짜 키보드"} " +
                    "(한글 ${if (ime?.isHangul == true) "켜짐" else "꺼짐"}, 입력칸 ${if (ime?.hasTextField == true) "있음" else "없음"}, 키보드 앱 ${if (ime != null) "실행 중" else "꺼짐"})")
            }
            // 한/영 키: 태블릿 입력칸이면 키보드 앱에서 한글 전환, 게임·지포스 나우 화면이면 진짜 한/영 키로 보냄
            var toIme = (vk == 0x15 && (ime?.hasTextField == true || (ime != null && focusedEditable()))) || hangulText
            // 누를 때 간 곳으로 뗄 때도 보냄 (중간에 한/영·입력칸 상태가 바뀌면 진짜 키가 안 떼어져서 gggg 처럼 계속 눌림)
            if (down) keyToIme[vk] = toIme
            else keyToIme.remove(vk)?.let { toIme = it }
            if (toIme || modifier) ime?.onRemoteKey(vk, down)
            if (vk == 0x1B) {
                // 안드로이드는 Esc만 누르면 '뒤로 가기'로 바꿔 버림 → Win+Esc로 보내야 앱(지포스 나우 등)에 진짜 Esc로 들어감
                // 누를 때 한 번에 '눌렀다 뗌'으로 보내고, 뗄 때는 보낼 것이 없음 (Win이 눌린 채 남지 않게)
                if (down) hid.tapMetaEsc()
                return
            }
            if (!toIme) hid.key(vk, down)
            return
        }
        if (ime == null && down && focusedEditable() && switchToKvmIme()) {
            toast("PC 키보드 입력: 'PC 키보드 공유' 키보드로 바꿨어요")
            main.postDelayed({ KvmIme.instance?.onRemoteKey(vk, true); KvmIme.instance?.onRemoteKey(vk, false) }, 500)
            return
        }
        ime?.onRemoteKey(vk, down)
    }

    /** 지금 화면에서 글자 입력칸에 커서가 있는지 (키보드 앱이 꺼져 있어도 알 수 있게) */
    private fun focusedEditable(): Boolean = try {
        findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
    } catch (_: Exception) { false }

    private fun kvmImeId() = android.content.ComponentName(this, KvmIme::class.java).flattenToShortString()

    private fun currentImeId(): String =
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: "?"

    private var lastImeSwitch = 0L
    private val keyToIme = HashMap<Int, Boolean>()
    private var editableAt = 0L
    private var editableVal = false

    /** 입력칸 여부를 잠깐 기억 (키마다 화면을 뒤지면 느려서) */
    private fun editableCached(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - editableAt > 400) { editableVal = focusedEditable(); editableAt = now }
        return editableVal
    }
    private var prevImeId: String? = null      // PC 입력 때문에 잠깐 바꾸기 전 키보드 (터치로 쓰던 것)

    /** 마우스가 메인 PC로 돌아가면 원래 쓰던 키보드로 되돌림 (손으로 칠 때 한글 키보드가 나오게) */
    private fun restoreUserIme() {
        val prev = prevImeId ?: return
        prevImeId = null
        if (Build.VERSION.SDK_INT < 30 || currentImeId() != kvmImeId()) return
        val ok = try { softKeyboardController.switchToInputMethod(prev) } catch (_: Exception) { false }
        sendLog("키보드 되돌림 → $prev : ${if (ok) "성공" else "실패"}")
    }

    /** 'PC 키보드 공유'를 지금 키보드로 (안드로이드 11 이상, 키보드 목록에서 켜 둔 경우) */
    private fun switchToKvmIme(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        if (SystemClock.uptimeMillis() - lastImeSwitch < 3000) return false
        lastImeSwitch = SystemClock.uptimeMillis()
        val id = kvmImeId()
        val before = currentImeId()
        if (before != id && before != "?") prevImeId = before
        val ok = try {
            softKeyboardController.switchToInputMethod(id)
        } catch (e: Exception) { sendLog("키보드 전환 실패: ${e.message}"); false }
        sendLog("키보드 전환 → $id : ${if (ok) "성공" else "실패"} (지금 ${currentImeId()})")
        return ok
    }

    private fun toast(text: String) = main.post {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 처리한 단축키면 true */
    private fun shortcut(vk: Int): Boolean {
        if (altDown) when (vk) {
            0x09 -> {                                      // Alt+Tab
                altTabCount++
                if (altTabCount == 2) performGlobalAction(GLOBAL_ACTION_RECENTS)   // 두 번째 Tab → 최근 앱 화면
                return true
            }
            0x73 -> { performGlobalAction(GLOBAL_ACTION_BACK); return true }      // Alt+F4 → 뒤로
            0x25 -> { performGlobalAction(GLOBAL_ACTION_BACK); return true }      // Alt+← → 뒤로
        }
        if (winDown) {
            winUsed = true
            when (vk) {
                0x44 -> performGlobalAction(GLOBAL_ACTION_HOME)                   // Win+D
                0x09 -> performGlobalAction(GLOBAL_ACTION_RECENTS)                // Win+Tab
                0x4E -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)          // Win+N
                0x41 -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)         // Win+A
                0x4C -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)  // Win+L
                0x53 -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) // Win+Shift+S
            }
            return true
        }
        when (vk) {
            0x1B -> if (hidOn) return false else performGlobalAction(GLOBAL_ACTION_BACK)   // Esc → 뒤로
            0x2C -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) // PrtSc
            0xAD -> volume(android.media.AudioManager.ADJUST_TOGGLE_MUTE)
            0xAE -> volume(android.media.AudioManager.ADJUST_LOWER)
            0xAF -> volume(android.media.AudioManager.ADJUST_RAISE)
            0xB0 -> media(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            0xB1 -> media(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            0xB3 -> media(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            else -> return false
        }
        return true
    }

    /** 최근 앱 버튼을 두 번 빠르게 누르면 직전 앱으로 전환됨 */
    private fun quickSwitch() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        main.postDelayed({ performGlobalAction(GLOBAL_ACTION_RECENTS) }, 350)
    }

    private fun volume(dir: Int) {
        val am = getSystemService(android.media.AudioManager::class.java)
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI)
    }

    private fun media(code: Int) {
        val am = getSystemService(android.media.AudioManager::class.java)
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    // ------------------------------------------------------------ 자동 업데이트
    private var apkCallback: ((java.io.File?) -> Unit)? = null

    private fun myVersionCode(): Long {
        val pi = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    private fun onApkInfo(name: String, code: Long) {
        val newer = code > myVersionCode()
        updateName = if (newer) name else null
        updateListener?.invoke()
        if (newer) showUpdateNotification(name)
    }

    /** MainActivity의 업데이트 버튼에서 호출. 서버에서 apk를 받아 파일로 넘겨줌 */
    fun requestApk(cb: (java.io.File?) -> Unit) {
        val n = net ?: return cb(null)
        apkCallback = cb
        n.send(JSONObject().put("t", "apk_get"))
    }

    private fun onApkData(b64: String) {
        val cb = apkCallback ?: return
        apkCallback = null
        Thread {
            val file = try {
                java.io.File(cacheDir, "update.apk").also {
                    it.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                }
            } catch (_: Exception) { null }
            main.post { cb(file) }
        }.start()
    }

    private fun showUpdateNotification(name: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(
            "update", "앱 업데이트", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(
            this, 1, android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(7, android.app.Notification.Builder(this, "update")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("PC 마우스 공유 v$name 업데이트")
            .setContentText("눌러서 바로 설치하세요")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    // ------------------------------------------------------------ 클립보드
    private fun setClipboard(text: String) {
        if (text.isEmpty()) return
        lastClip = text
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PC", text))
    }

    /** 안드로이드 10+에서는 'PC 키보드 공유'가 현재 키보드일 때만 읽기 가능 */
    private fun sendClipboardIfChanged() {
        val text = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        if (!text.isNullOrEmpty() && text != lastClip) {
            lastClip = text
            net?.send(JSONObject().put("t", "clip").put("text", text))
        }
    }

    // ------------------------------------------------------------ 화살표 커서
    private class CursorView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2.5f
        }

        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            val p = Path().apply {
                moveTo(1f, 1f)
                lineTo(1f, s * 0.85f)
                lineTo(s * 0.28f, s * 0.64f)
                lineTo(s * 0.48f, s * 0.98f)
                lineTo(s * 0.62f, s * 0.9f)
                lineTo(s * 0.42f, s * 0.57f)
                lineTo(s * 0.72f, s * 0.57f)
                close()
            }
            canvas.drawPath(p, fill)
            canvas.drawPath(p, stroke)
        }
    }
}

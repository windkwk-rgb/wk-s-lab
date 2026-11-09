package com.penbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.concurrent.thread

/** 전체 화면 펜 입력판. 화면에 보이는 동안만 연결을 유지한다. */
class PadActivity : AppCompatActivity(), PenConnection.Listener, StreamClient.Listener {

    private lateinit var prefs: Prefs
    private lateinit var pad: PenPadView
    private lateinit var host: String
    private var isUsb = false
    private var connection: PenConnection? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var updating = false
    /** 연결된(또는 저장된) PC 이름. IP가 바뀌면 이 이름으로 다시 찾음 */
    private var pcName: String? = null
    private var searchingNewIp = false
    private var lastSearchAt = 0L
    private var stream: StreamClient? = null
    private val toolbar = mutableStateOf(ToolbarState())
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        host = intent.getStringExtra(EXTRA_HOST) ?: run { finish(); return }
        isUsb = host == USB_HOST
        pcName = intent.getStringExtra(EXTRA_NAME)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        pad = PenPadView(this, sink = { connection?.send(it) }, onCloseTap = { finish() })
        pad.fingerInput = prefs.fingerInput
        pad.showCursor = prefs.showCursor
        pad.gesturesEnabled = prefs.gestures
        pad.mirrorMode = prefs.mirror
        toolbar.value = ToolbarState(mirror = prefs.mirror, collapsed = prefs.toolbarCollapsed, right = prefs.toolbarRight)

        // [툴바][입력판] (왼손잡이 설정이면 [입력판][툴바]) — 툴바가 차지한 만큼 입력판이 줄어서 서로 겹치지 않음
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#141519"))
        }
        val padParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        if (prefs.toolbarEnabled) {
            val bar = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    PadToolbar(
                        state = toolbar.value,
                        onAction = ::onToolbarAction,
                        onMirror = ::toggleMirror,
                        onCollapse = ::toggleCollapse,
                    )
                }
            }
            val barParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (prefs.toolbarRight) { root.addView(pad, padParams); root.addView(bar, barParams) }
            else { root.addView(bar, barParams); root.addView(pad, padParams) }
        } else {
            root.addView(pad, padParams)
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    override fun onStart() {
        super.onStart()
        if (!isUsb) acquireWifiLock()
        connect()
        if (prefs.mirror) startStream()
    }

    override fun onStop() {
        pad.releasePen()
        stopStream()
        connection?.close()
        connection = null
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onStop()
    }

    private fun connect() {
        connection?.close()
        val pin = if (isUsb) 0 else prefs.pinFor(host).coerceAtLeast(0)
        connection = PenConnection(host, pin, AppUpdater.versionCode(this), prefs.autoUpdate, this).apply {
            viewWidth = pad.width
            viewHeight = pad.height
            start()
        }
    }

    /** Wi‑Fi 절전으로 인한 끊김/지연을 막음 */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                   else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "PenBridge").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ───────────── PenConnection.Listener (백그라운드 스레드) ─────────────

    override fun onStatus(status: PenConnection.Status, detail: String?) {
        runOnUiThread {
            val via = if (isUsb) "USB" else "Wi‑Fi"
            when (status) {
                PenConnection.Status.CONNECTING -> pad.setStatus("$via 연결 중…", null)
                PenConnection.Status.CONNECTED -> {
                    connected = true
                    val name = detail ?: pcName ?: if (isUsb) "USB" else host
                    pcName = name
                    prefs.rememberPc(name, host)   // 다음부터 목록에서 바로 연결
                    pad.setStatus("$name · $via", true)
                }
                PenConnection.Status.RECONNECTING -> {
                    connected = false
                    pad.setStatus(
                        if (isUsb) "다시 연결 중… (노트북 PenBridge에 태블릿이 보이는지 확인)" else "다시 연결 중…", false, -1)
                    if ((connection?.failures ?: 0) >= 2) findMovedPc()
                }
                PenConnection.Status.REJECTED -> askPin()
                PenConnection.Status.CLOSED -> {}
            }
        }
    }

    /** 노트북 IP가 바뀌었을 수 있음 → 같은 이름의 PC를 다시 찾아서 이어 붙임 */
    private fun findMovedPc() {
        val name = pcName ?: return
        if (isUsb || searchingNewIp || isFinishing) return
        if (System.currentTimeMillis() - lastSearchAt < 15_000) return
        lastSearchAt = System.currentTimeMillis()
        searchingNewIp = true
        pad.setStatus("'$name'을(를) 다시 찾는 중…", null, -1)
        thread(isDaemon = true, name = "PenBridge find") {
            val found = try { Discovery.findByName(this, name) } catch (_: Exception) { null }
            runOnUiThread {
                searchingNewIp = false
                if (found != null && found.address != host && !isFinishing) {
                    val pin = prefs.pinFor(host)
                    if (pin >= 0 && prefs.pinFor(found.address) < 0) prefs.setPin(found.address, pin)
                    host = found.address
                    prefs.rememberPc(name, host)
                    connect()
                    if (prefs.mirror) startStream()
                }
            }
        }
    }

    override fun onToolState(eraser: Boolean) {
        runOnUiThread { toolbar.value = toolbar.value.copy(eraser = eraser) }
    }

    // ───────────── 툴바 ─────────────

    private fun onToolbarAction(id: Int, down: Boolean, label: String) {
        val c = connection
        if (!connected || c == null) {
            if (down) pad.flash("노트북에 연결되지 않았어요")
            return
        }
        c.send(Protocol.action(id, down))
        if (down) pad.flash(label)
    }

    private fun toggleCollapse() {
        val v = !toolbar.value.collapsed
        prefs.toolbarCollapsed = v
        toolbar.value = toolbar.value.copy(collapsed = v)
    }

    // ───────────── 액정 타블렛 모드 ─────────────

    private fun toggleMirror() {
        val on = !toolbar.value.mirror
        prefs.mirror = on
        toolbar.value = toolbar.value.copy(mirror = on)
        pad.mirrorMode = on
        if (on) { startStream(); pad.flash("노트북 화면 보기") } else { stopStream(); pad.flash("노트북 화면 끄기") }
    }

    private fun startStream() {
        stopStream()
        val pin = if (isUsb) 0 else prefs.pinFor(host).coerceAtLeast(0)
        val maxW = resources.displayMetrics.widthPixels
        stream = StreamClient(host, pin, maxW, this).also { it.start() }
    }

    private fun stopStream() {
        stream?.close()
        stream = null
    }

    override fun onFrame(bitmap: Bitmap) = pad.setFrame(bitmap)

    override fun onStreamState(state: StreamClient.State) {
        if (state != StreamClient.State.DENIED) return
        runOnUiThread {
            prefs.mirror = false
            toolbar.value = toolbar.value.copy(mirror = false)
            pad.mirrorMode = false
            stream = null
            Toast.makeText(this, "노트북에서 화면 보내기가 꺼져 있어요. 노트북 PenBridge의 '펜 · 화면'에서 켜 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onAspect(aspect: Float) {
        runOnUiThread { pad.setAspect(aspect) }
    }

    override fun onLatency(ms: Long) {
        runOnUiThread { pad.setLatency(ms) }
    }

    override fun onUpdateOffer(versionCode: Int, size: Long) {
        runOnUiThread { startUpdate(versionCode) }
    }

    // ───────────── 자동 업데이트 ─────────────

    private fun startUpdate(newVersion: Int) {
        if (updating || isFinishing || !prefs.autoUpdate) return
        if (newVersion <= AppUpdater.versionCode(this) || newVersion == prefs.skippedVersion) return

        // 처음 한 번: "이 출처의 앱 설치 허용" 필요
        if (!packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("새 버전 v${AppUpdater.versionString(newVersion)}")
                .setMessage("노트북에 새 버전이 있어요. 자동으로 설치하려면 다음 화면에서 'PenBridge'의 '이 출처 허용'을 켜 주세요. 켠 뒤 다시 연결하면 설치돼요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("이번 버전 건너뛰기") { _, _ -> prefs.skippedVersion = newVersion }
                .show()
            return
        }

        updating = true
        pad.setStatus("새 버전 받는 중…", null, -1)
        val pin = if (isUsb) 0 else prefs.pinFor(host).coerceAtLeast(0)
        val file = File(cacheDir, "update.apk")
        thread(isDaemon = true, name = "PenBridge update") {
            try {
                AppUpdater.download(host, pin, file) { pct ->
                    runOnUiThread { pad.setStatus("새 버전 받는 중… $pct%", null, -1) }
                }
                runOnUiThread { pad.setStatus("새 버전 설치 중… (끝나면 앱을 다시 열어 주세요)", null, -1) }
                AppUpdater.install(this, file)
            } catch (e: Exception) {
                runOnUiThread {
                    updating = false
                    pad.setStatus("업데이트 실패: ${e.message ?: "알 수 없는 오류"}", false, -1)
                }
            }
        }
    }

    private fun askPin() {
        if (isFinishing) return
        pad.setStatus("PIN 필요", false, -1)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "0000"
            textSize = 24f
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("PIN 입력")
            .setMessage("노트북의 PenBridge 창에 보이는 4자리 숫자를 입력하세요.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("연결") { _, _ ->
                val pin = input.text.toString().trim().toIntOrNull()
                if (pin == null) { finish(); return@setPositiveButton }
                prefs.setPin(host, pin)
                connect()
            }
            .setNegativeButton("취소") { _, _ -> finish() }
            .show()
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_NAME = "name"
        const val USB_HOST = "127.0.0.1"
    }
}

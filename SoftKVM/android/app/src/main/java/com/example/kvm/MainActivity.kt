package com.example.kvm

import android.app.Activity
import org.json.JSONObject
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 설정 화면: 연결 상태, 서버 정보 입력, 설정 단계 체크리스트 */
class MainActivity : Activity() {

    private object C {
        val BG = 0xFF0F1115.toInt()
        val CARD = 0xFF171A21.toInt()
        val FIELD = 0xFF20242D.toInt()
        val TEXT = 0xFFECEEF1.toInt()
        val SUB = 0xFF8B919C.toInt()
        val BLUE = 0xFF2563EB.toInt()
        val GREEN = 0xFF22C55E.toInt()
        val AMBER = 0xFFF59E0B.toInt()
        val RED = 0xFFEF4444.toInt()
        val LINE = 0xFF262A33.toInt()
    }

    private var dp = 1f
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private val stepRows = ArrayList<Pair<() -> Boolean, TextView>>()
    private lateinit var updateCard: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var updateBtn: TextView
    private var installing = false
    private lateinit var connCard: LinearLayout
    private lateinit var statusCard: View
    private lateinit var formCard: View
    private lateinit var stepsCard: View
    private lateinit var miniMap: MiniMap
    private lateinit var cName: TextView
    private lateinit var cSub: TextView
    private lateinit var pill: LinearLayout
    private lateinit var pillDot: View
    private lateinit var pillText: TextView
    private lateinit var cTime: TextView
    private lateinit var cPeers: TextView
    private var editing = false
    // 매크로 카드
    private lateinit var mcCard: LinearLayout
    private lateinit var mcTitle: TextView
    private lateinit var mcText: TextView
    private lateinit var mcRec: TextView
    private lateinit var mcPlay: TextView
    private lateinit var mcRecKey: TextView
    private lateinit var mcPlayKey: TextView
    private lateinit var mcRepeat: TextView
    private lateinit var mcName: EditText
    private lateinit var mcList: LinearLayout
    private var mcListKey = ""
    private lateinit var mcOff: TextView
    private lateinit var mcClear: TextView
    private lateinit var mcNameBtn: TextView
    private lateinit var mcHint: TextView
    private lateinit var mcSwKey: TextView
    private lateinit var tileRec: LinearLayout
    private lateinit var tilePlay: LinearLayout
    private lateinit var tileSw: LinearLayout
    private var pages: List<LinearLayout> = emptyList()
    private val tabs = ArrayList<TextView>()
    private var savedTab = 0

    private fun selectTab(i: Int) {
        savedTab = i
        pages.forEachIndexed { j, pg -> pg.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { j, t ->
            t.setTextColor(if (j == i) C.TEXT else C.SUB)
            t.background = if (j == i) round(C.FIELD, 10) else null
        }
    }
    private var resumedAt = 0L
    private lateinit var ipSwitch: TextView
    private lateinit var awakeSwitch: TextView
    private lateinit var ipStatus: TextView
    private lateinit var ipHelp: View
    private lateinit var ipOrient: LinearLayout
    private lateinit var ipOrientBtn: TextView
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refreshLive(); ticker.postDelayed(this, 1000) } }

    companion object { const val ACTION_INSTALL = "com.example.kvm.INSTALL_STATUS" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        val p = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(28))
        }

        // 제목
        val appVer = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("PC 마우스 공유", 26f, C.TEXT, bold = true))
            addView(text("v$appVer", 13f, C.SUB, bold = true).apply {
                background = round(C.CARD, 12)
                setPadding(px(10), px(4), px(10), px(4))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(10) }
            })
        })
        col.addView(text("윈도우의 마우스·키보드로 이 기기를 조작합니다", 14f, C.SUB).apply {
            setPadding(0, px(4), 0, px(20))
        })

        // 업데이트 카드 (새 버전이 있을 때만 보임)
        updateCard = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(0xFF14213D.toInt(), 18, 0xFF2B4A8C.toInt())
            visibility = View.GONE
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                updateTitle = text("", 16f, C.TEXT, bold = true)
                addView(updateTitle)
                addView(text("PC에서 받아 바로 설치해요", 13f, C.SUB))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            updateBtn = button("업데이트") { startUpdate() }.apply { textSize = 14f; setPadding(px(18), 0, px(18), 0) }
            addView(updateBtn, LinearLayout.LayoutParams(-2, px(42)))
        }
        col.addView(updateCard)

        // 탭: 연결 · 매크로 · 설정 (세로로 너무 길지 않게)
        val pageConn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pageMacro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pageSet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages = listOf(pageConn, pageMacro, pageSet)
        col.addView(LinearLayout(this).apply {
            setPadding(px(4), px(4), px(4), px(4))
            background = round(C.CARD, 14)
            listOf("연결", "매크로", "설정").forEachIndexed { i, label ->
                val t = TextView(this@MainActivity).apply {
                    text = label; textSize = 15f; gravity = Gravity.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isClickable = true
                    setOnClickListener { selectTab(i) }
                }
                tabs.add(t)
                addView(t, LinearLayout.LayoutParams(0, px(42), 1f))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
        pages.forEach { col.addView(it) }

        // 연결됨 화면 (연결되면 설정 대신 이 카드가 보임)
        connCard = card().apply {
            visibility = View.GONE
            miniMap = MiniMap(this@MainActivity)
            addView(miniMap, LinearLayout.LayoutParams(-1, px(150)))
            cName = text("", 22f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) }
            cSub = text("", 13f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(0, px(4), 0, px(14)) }
            addView(cName); addView(cSub)
            pill = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                setPadding(px(14), px(12), px(14), px(12))
                pillDot = View(this@MainActivity)
                addView(pillDot, LinearLayout.LayoutParams(px(9), px(9)).apply { marginEnd = px(10) })
                pillText = text("", 14f, C.SUB)
                addView(pillText)
            }
            addView(pill)
            addView(LinearLayout(this@MainActivity).apply {
                setPadding(0, px(12), 0, 0)
                fun stat(label: String): TextView {
                    val v = text("–", 16f, C.TEXT, bold = true)
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = round(C.FIELD, 12)
                        setPadding(px(14), px(12), px(14), px(12))
                        addView(text(label, 12f, C.SUB)); addView(v)
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(5); marginStart = px(5) })
                    return v
                }
                cTime = stat("연결 시간"); cPeers = stat("함께 연결된 기기")
            })
            addView(TextView(this@MainActivity).apply {
                text = "연결 설정 변경"; textSize = 15f; setTextColor(C.TEXT); gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = round(C.FIELD, 12, 0xFF2E3340.toInt())
                isClickable = true
                setOnClickListener { editing = true; refreshLive() }
            }, LinearLayout.LayoutParams(-1, px(46)).apply { topMargin = px(14) })
        }
        pageConn.addView(connCard)
        mcOff = text("메인 PC와 연결되면 여기서 매크로를 녹화·실행·관리할 수 있어요", 14f, C.SUB).apply {
            gravity = Gravity.CENTER; setPadding(px(20), px(40), px(20), px(40))
        }
        pageMacro.addView(mcOff)
        pageMacro.addView(buildMacroCard())

        // 상태 카드
        statusCard = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            statusDot = View(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.SUB) }
            }
            addView(statusDot, LinearLayout.LayoutParams(px(12), px(12)).apply { marginEnd = px(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                statusText = text("", 16f, C.TEXT, bold = true)
                statusSub = text("", 13f, C.SUB)
                addView(statusText); addView(statusSub)
            })
        }
        pageConn.addView(statusCard)

        // 서버 연결 카드
        val host = field(p.host, "예: 192.168.0.10", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field(p.port.toString(), "24800", InputType.TYPE_CLASS_NUMBER)
        val name = field(p.name, "android", InputType.TYPE_CLASS_TEXT)
        val key = field(p.key, "서버 config.json의 key",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        formCard = card().apply {
            addView(section("서버 연결"))
            addView(label("서버 주소"))
            addView(LinearLayout(this@MainActivity).apply {
                addView(host, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
                addView(port, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = px(10) })
            })
            addView(label("기기 이름"))
            addView(name)
            addView(label("비밀번호"))
            addView(key)
            addView(button("저장하고 연결") {
                p.host = host.text.toString()
                p.port = port.text.toString().toIntOrNull() ?: 24800
                p.name = name.text.toString().ifBlank { "android" }
                p.key = key.text.toString()
                editing = false
                val svc = KvmAccessibilityService.instance
                if (svc != null) svc.reconnect()
                else toast("아래 설정 단계 1번을 먼저 완료해 주세요")
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)).apply { topMargin = px(20) })
        }
        pageConn.addView(formCard)

        // 설정 단계 카드
        stepsCard = card().apply {
            addView(section("설정 단계"))
            addView(step("1", "접근성에서 'PC 마우스 공유' 켜기", "커서 표시와 탭·스크롤에 필요",
                ::accessibilityOn) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            addView(divider())
            addView(step("2", "키보드 목록에서 'PC 키보드 공유' 켜기", "타이핑과 한글 입력에 필요",
                ::imeEnabled) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
            addView(divider())
            addView(step("3", "평소 키보드는 그대로 (자동 전환)", "PC에서 한/영을 누르면 'PC 키보드 공유'로 잠깐 바뀌고, 마우스가 돌아가면 원래 키보드로",
                ::imeEnabled) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            })
            addView(divider())
            addView(step("4", "배터리 사용 '제한 없음'으로", "끊김 방지: 태블릿이 앱을 재우지 않게",
                ::batteryOk) { askBattery() })
        }
        pageConn.addView(stepsCard)
        pageConn.addView(buildProfileCard())

        // 화면 옵션
        pageSet.addView(card().apply {
            addView(section("화면"))
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("연결 중 화면 켜두기", 15f, C.TEXT, bold = true))
                    addView(text("메인 PC와 연결돼 있는 동안 화면이 꺼지지 않아요. 마우스가 넘어오면 꺼진 화면도 켜져요", 13f, C.SUB)
                        .apply { setPadding(0, px(2), px(10), 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                awakeSwitch = TextView(this@MainActivity).apply {
                    textSize = 14f; gravity = Gravity.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isClickable = true
                    setOnClickListener {
                        val pr = Prefs(this@MainActivity); pr.keepAwake = !pr.keepAwake
                        KvmAccessibilityService.instance?.updateKeepAwake()
                        drawAwakeSwitch()
                    }
                }
                addView(awakeSwitch, LinearLayout.LayoutParams(px(76), px(38)))
            })
        })
        drawAwakeSwitch()

        // 배터리 표시 (이 태블릿 배터리를 이 화면에만)
        pageSet.addView(card().apply {
            addView(section("배터리 표시"))
            val sw = TextView(this@MainActivity).apply {
                textSize = 14f; gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isClickable = true
            }
            val posBtns = ArrayList<Pair<String, TextView>>()
            fun draw() {
                val pr = Prefs(this@MainActivity)
                sw.text = if (pr.batteryOverlay) "켜짐" else "꺼짐"
                sw.setTextColor(if (pr.batteryOverlay) Color.WHITE else C.SUB)
                sw.background = round(if (pr.batteryOverlay) C.GREEN else C.FIELD, 19)
                for ((v, b) in posBtns) {
                    val on = pr.batteryPos == v
                    b.setTextColor(if (on) Color.WHITE else C.SUB)
                    b.background = round(if (on) C.BLUE else C.FIELD, 9)
                }
                KvmAccessibilityService.instance?.updateBatteryBadge()
            }
            sw.setOnClickListener { val pr = Prefs(this@MainActivity); pr.batteryOverlay = !pr.batteryOverlay; draw() }
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("이 태블릿 배터리를 화면에 표시", 15f, C.TEXT, bold = true))
                    addView(text("게임 화면 위에도 작게 보여요 · 20% 아래면 빨간색", 13f, C.SUB).apply { setPadding(0, px(2), px(10), 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(sw, LinearLayout.LayoutParams(px(76), px(38)))
            })
            addView(text("표시 위치 · 화면 그림에서 눌러 고르세요", 13f, C.SUB).apply { setPadding(0, px(14), 0, px(8)) })
            // 태블릿 화면 모양의 틀 안에 8곳 (가운데는 화면)
            val frame = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(10), px(10), px(10), px(2))
                background = round(0xFF141821.toInt(), 14, 0xFF2C313C.toInt())
            }
            addView(frame)
            for (row in listOf(listOf("tl" to "좌상단", "tc" to "상단 중간", "tr" to "우상단"),
                               listOf("ml" to "좌중단", "" to "", "mr" to "우중단"),
                               listOf("bl" to "좌하단", "bc" to "하단 중간", "br" to "우하단"))) {
                frame.addView(LinearLayout(this@MainActivity).apply {
                    for ((i, pr) in row.withIndex()) {
                        if (pr.first.isEmpty()) {          // 가운데: 화면
                            addView(text("화면", 12f, 0xFF5D6370.toInt()).apply { gravity = Gravity.CENTER },
                                LinearLayout.LayoutParams(0, px(40), 1f).apply { marginStart = px(8) })
                            continue
                        }
                        val b = TextView(this@MainActivity).apply {
                            text = pr.second; textSize = 14f; gravity = Gravity.CENTER
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            isClickable = true
                            setOnClickListener { Prefs(this@MainActivity).batteryPos = pr.first; draw() }
                        }
                        posBtns.add(pr.first to b)
                        addView(b, LinearLayout.LayoutParams(0, px(40), 1f).apply {
                            if (i > 0) marginStart = px(8); bottomMargin = px(8)
                        })
                    }
                })
            }
            draw()
        })

        // 아이폰 중계 카드
        pageSet.addView(card().apply {
            addView(section("아이폰 연결"))
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("아이폰도 함께 조작", 15f, C.TEXT, bold = true))
                    ipStatus = text("", 13f, C.SUB).apply { setPadding(0, px(2), 0, 0) }
                    addView(ipStatus)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                ipSwitch = TextView(this@MainActivity).apply {
                    textSize = 14f; gravity = Gravity.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isClickable = true
                    setOnClickListener { toggleIphone() }
                }
                addView(ipSwitch, LinearLayout.LayoutParams(px(76), px(38)))
            })
            ipOrient = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(14), 0, 0)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("아이폰 방향", 15f, C.TEXT, bold = true))
                    addView(text("아이폰을 눕혀서 쓰면 가로로 바꿔 주세요", 13f, C.SUB))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                ipOrientBtn = TextView(this@MainActivity).apply {
                    textSize = 14f; gravity = Gravity.CENTER; setTextColor(C.TEXT)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    background = round(C.FIELD, 19, 0xFF2E3340.toInt())
                    isClickable = true
                    setOnClickListener {
                        val r = KvmAccessibilityService.instance?.iphone ?: return@setOnClickListener
                        r.setLandscape(!Prefs(this@MainActivity).iphoneLandscape)
                        refreshIphone()
                    }
                }
                addView(ipOrientBtn, LinearLayout.LayoutParams(px(76), px(38)))
            }
            addView(ipOrient)
            ipHelp = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(12), 0, 0)
                listOf(
                    "1. 아이폰: 설정 → 손쉬운 사용 → 터치 → AssistiveTouch 켜기 (커서 표시용)",
                    "2. 아래 '아이폰에서 찾기 허용'을 누른 뒤",
                    "3. 아이폰: 설정 → 블루투스 → 이 태블릿 이름을 눌러 연결",
                    "4. 연결되면 메인 PC 배치 창에 iphone 상자가 생겨요",
                ).forEach { addView(text(it, 13f, C.SUB).apply { setPadding(0, px(3), 0, px(3)) }) }
                addView(button("아이폰에서 찾기 허용") { makeDiscoverable() },
                    LinearLayout.LayoutParams(-1, px(46)).apply { topMargin = px(10) })
            }
            addView(ipHelp)
        })

        // 조작법 카드 (두 줄씩)
        pageSet.addView(card().apply {
            addView(section("조작법"))
            listOf(
                "왼쪽 클릭" to "탭", "누른 채 끌기" to "드래그", "오래 누르기" to "길게 누르기",
                "오른쪽 클릭 · Esc" to "뒤로", "가운데 클릭" to "홈", "휠" to "스크롤",
                "한/영" to "한글 ↔ 영문", "Alt+Tab" to "직전 앱 (Tab 두 번 = 최근 앱)",
                "Win" to "홈", "Win+N · Win+A" to "알림 · 빠른 설정", "Win+L" to "화면 잠금",
                "PrtSc · Win+Shift+S" to "스크린샷", "Alt+F4 · Alt+←" to "뒤로", "볼륨·재생 키" to "그대로 동작",
                "Ctrl+Alt+Home" to "윈도우로 복귀 (메인 PC에서 변경 가능)",
            ).chunked(2).forEach { pair ->
                addView(LinearLayout(this@MainActivity).apply {
                    setPadding(0, px(6), 0, px(6))
                    pair.forEachIndexed { i, (k, v) ->
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text(k, 14f, C.TEXT, bold = true))
                            addView(text(v, 13f, C.SUB))
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 1) marginStart = px(12) })
                    }
                    if (pair.size == 1) addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
                })
            }
        })
        selectTab(0)

        // 태블릿에서도 보기 좋게 가운데 정렬 + 최대 폭 제한
        val frame = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        frame.addView(col, FrameLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels, px(640)), -2, Gravity.CENTER_HORIZONTAL))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(C.BG)
            fitsSystemWindows = true   // 상태바·내비게이션 바와 겹치지 않게
            isFillViewport = true
            addView(frame)
        })
    }

    override fun onResume() {
        super.onResume()
        resumedAt = System.currentTimeMillis()
        KvmAccessibilityService.statusListener = { s -> runOnUiThread { showStatus(s) } }
        showStatus(KvmAccessibilityService.lastStatus)
        KvmAccessibilityService.updateListener = { runOnUiThread { showUpdate() } }
        KvmAccessibilityService.liveListener = { runOnUiThread { refreshLive() } }
        KvmAccessibilityService.iphoneListener = { runOnUiThread { refreshIphone() } }
        refreshIphone()
        ticker.post(tick)
        showUpdate()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)   // 업데이트 알림용
        }
        stepRows.forEach { (check, badge) -> setBadge(badge, check()) }
    }

    // 키보드 선택 창은 화면을 덮는 대화상자라 onResume이 안 불림 → 창이 닫혀 포커스가 돌아올 때도 갱신
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) stepRows.forEach { (check, badge) -> setBadge(badge, check()) }
    }

    override fun onPause() {
        KvmAccessibilityService.statusListener = null
        KvmAccessibilityService.updateListener = null
        KvmAccessibilityService.liveListener = null
        KvmAccessibilityService.iphoneListener = null
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun drawAwakeSwitch() {
        val on = Prefs(this).keepAwake
        awakeSwitch.text = if (on) "켜짐" else "꺼짐"
        awakeSwitch.setTextColor(if (on) Color.WHITE else C.SUB)
        awakeSwitch.background = round(if (on) C.GREEN else C.FIELD, 19)
    }

    // ------------------------------------------------------------ 아이폰 중계
    private val btPerms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()

    private fun hasBtPerms() = btPerms.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    private fun toggleIphone() {
        val svc = KvmAccessibilityService.instance ?: return toast("설정 단계 1번(접근성)을 먼저 켜 주세요")
        if (Build.VERSION.SDK_INT < 28) return toast("이 기기 안드로이드 버전에서는 지원하지 않아요")
        val on = svc.iphone == null
        if (on && !hasBtPerms()) { requestPermissions(btPerms, 3); return }
        svc.setIphone(on)
        refreshIphone()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3) {
            if (hasBtPerms()) { KvmAccessibilityService.instance?.setIphone(true); refreshIphone() }
            else toast("'근처 기기' 권한을 허용해야 아이폰과 연결할 수 있어요")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun makeDiscoverable() {
        if (!hasBtPerms()) { requestPermissions(btPerms, 3); return }
        startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180))
    }

    private fun refreshIphone() {
        val r = KvmAccessibilityService.instance?.iphone
        val on = r != null
        ipSwitch.text = if (on) "켜짐" else "꺼짐"
        ipSwitch.setTextColor(if (on) Color.WHITE else C.SUB)
        ipSwitch.background = round(if (on) C.GREEN else C.FIELD, 19)
        ipStatus.text = when {
            r == null -> "켜면 이 태블릿이 아이폰의 블루투스 마우스·키보드가 돼요"
            r.netStatus.startsWith("연결됨") -> "${r.btStatus} · 메인 PC와 연결됨"
            else -> r.btStatus + (if (r.netStatus.isNotBlank()) " · ${r.netStatus}" else "")
        }
        ipHelp.visibility = if (on && r?.hostName == null) View.VISIBLE else View.GONE
        ipOrient.visibility = if (on) View.VISIBLE else View.GONE
        ipOrientBtn.text = if (Prefs(this).iphoneLandscape) "가로" else "세로"
    }

    // ------------------------------------------------------------ 연결됨 화면
    private fun refreshLive() {
        val S = KvmAccessibilityService
        val connected = S.instance != null && S.lastStatus.startsWith("연결됨")
        val show = connected && !editing
        if (!connected) editing = false
        connCard.visibility = if (show) View.VISIBLE else View.GONE
        statusCard.visibility = if (show) View.GONE else View.VISIBLE
        formCard.visibility = if (show) View.GONE else View.VISIBLE
        val stepsDone = accessibilityOn() && imeEnabled() && batteryOk()
        stepsCard.visibility = if (show && stepsDone) View.GONE else View.VISIBLE
        if (!show) { mcCard.visibility = View.GONE; mcOff.visibility = View.VISIBLE; return }
        val sideKo = mapOf("left" to "왼쪽", "right" to "오른쪽", "top" to "위", "bottom" to "아래")[S.side]
        cName.text = Prefs(this).name
        cSub.text = "${S.serverName.ifBlank { "메인 PC" }}에 연결됨" + (sideKo?.let { " · 메인 PC의 $it" } ?: "") +
            if (S.hidOn) " · 진짜 마우스 모드" else ""
        miniMap.side = S.side.ifBlank { "left" }
        miniMap.active = S.active
        miniMap.invalidate()
        pillText.text = when {
            S.active -> "지금 이 기기를 조작하고 있어요"
            sideKo != null -> "메인 PC 화면 $sideKo 끝으로 마우스를 밀면 이 기기로 넘어와요"
            else -> "메인 PC 배치 창에서 이 기기 위치를 정해 주세요"
        }
        pillText.setTextColor(if (S.active) 0xFFBFF3DA.toInt() else C.SUB)
        pill.background = if (S.active) round(0x173DDC97, 12, 0x593DDC97) else round(C.FIELD, 12)
        pillDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(if (S.active) C.GREEN else 0xFF5D6370.toInt())
        }
        val sec = ((System.currentTimeMillis() - S.connectedAt) / 1000).coerceAtLeast(0)
        cTime.text = when {
            sec < 60 -> "방금"
            sec < 3600 -> "${sec / 60}분"
            else -> "${sec / 3600}시간 ${(sec % 3600) / 60}분"
        }
        cPeers.text = if (S.peers.isEmpty()) "없음" else S.peers.joinToString(", ")
        refreshMacro()
    }

    // ------------------------------------------------------------ 매크로 (메인 PC 서버가 녹화·실행, 여기서는 조작·관리)
    private fun chip(s: String, onClick: (() -> Unit)? = null) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(C.TEXT); gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(px(12), px(7), px(12), px(7))
        background = round(C.FIELD, 9)
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }

    private fun macroRow(title: String, sub: TextView, vararg right: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(12), 0, px(12))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, C.TEXT, bold = true))
            addView(sub)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        right.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) }) }
    }

    private fun mc(op: String, extra: JSONObject = JSONObject()) {
        val svc = KvmAccessibilityService.instance
        if (svc == null || !KvmAccessibilityService.lastStatus.startsWith("연결됨") || !svc.sendMacro(op, extra))
            toast("메인 PC와 연결된 상태에서 써 주세요")
    }

    /** 녹화·실행: 이 화면을 내리고 원래 앱(지포스 나우 등)으로 돌아가서 시작. 끝낼 때는 이 화면을 누른 동작은 기록에서 뺌 */
    private fun mcStartStop(op: String) {
        val st = KvmAccessibilityService.macroState
        val busy = st != null && (st.optBoolean("recording") || st.optBoolean("playing"))
        val trim = ((System.currentTimeMillis() - resumedAt) / 1000.0 + 0.4).coerceAtMost(10.0)
        if (busy) { mc(op, JSONObject().put("trim", trim)); return }
        mc(op, JSONObject().put("delay", 1.0))
        moveTaskToBack(true)
    }

    private fun renameMacro(old: String) {
        // old가 비어 있으면 아직 저장 안 한 지금 녹화 → 이름을 붙여 목록에 저장
        val input = field(old, "새 이름", InputType.TYPE_CLASS_TEXT).apply { setSelection(old.length) }
        val box = FrameLayout(this).apply {
            setPadding(px(20), px(8), px(20), 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this).setTitle(if (old.isEmpty()) "이름 붙여 저장" else "이름 바꾸기").setView(box)
            .setPositiveButton(if (old.isEmpty()) "저장" else "바꾸기") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() || n == old) return@setPositiveButton
                if (old.isEmpty()) mc("save", JSONObject().put("name", n))
                else mc("rename", JSONObject().put("name", old).put("new", n))
            }
            .setNegativeButton("취소", null).show()
    }

    // ------------------------------------------------------------ 연결 프로필 (집·회사 등 메인 PC 주소·비밀번호 저장)
    private lateinit var pfList: LinearLayout

    private fun buildProfileCard(): LinearLayout {
        val c = card()
        c.addView(section("연결 프로필"))
        c.addView(text("집·회사처럼 메인 PC 주소와 비밀번호를 저장해 두면 한 번에 바꿔 연결할 수 있어요", 13f, C.SUB)
            .apply { setPadding(0, 0, 0, px(10)) })
        val nameField = field("", "지금 연결 정보 저장 (예: 집, 회사)", InputType.TYPE_CLASS_TEXT)
        c.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(nameField, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("저장") {
                val n = nameField.text.toString().trim()
                if (n.isEmpty()) return@button toast("이름을 입력하세요")
                val pr = Prefs(this@MainActivity)
                val all = JSONObject(pr.profiles)
                all.put(n, JSONObject().put("host", pr.host).put("port", pr.port).put("name", pr.name).put("key", pr.key)
                    .put("saved", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).format(java.util.Date())))
                pr.profiles = all.toString(); pr.profileCurrent = n
                nameField.setText("")
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(nameField.windowToken, 0)
                toast("'$n' 저장했어요"); drawProfiles()
            }, LinearLayout.LayoutParams(px(76), px(46)).apply { marginStart = px(8) })
        })
        pfList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(pfList)
        drawProfiles()
        return c
    }

    private fun drawProfiles() {
        val pr = Prefs(this)
        val all = try { JSONObject(pr.profiles) } catch (_: Exception) { JSONObject() }
        pfList.removeAllViews()
        val names = all.keys().asSequence().toList().sortedByDescending { all.optJSONObject(it)?.optString("saved") ?: "" }
        for (n in names) {
            val d = all.getJSONObject(n)
            val sub = text("${d.optString("host")}:${d.optInt("port", 24800)} · ${d.optString("saved")}", 12f, C.SUB)
            val use: View = if (n == pr.profileCurrent) text("사용 중", 12f, C.GREEN, bold = true).apply {
                setPadding(px(10), px(6), px(10), px(6)); background = round(0x1F22C55E, 8)
            } else chip("불러오기") {
                pr.host = d.optString("host"); pr.port = d.optInt("port", 24800)
                pr.name = d.optString("name", pr.name); pr.key = d.optString("key", pr.key)
                pr.profileCurrent = n
                KvmAccessibilityService.instance?.reconnect()
                toast("'$n'(으)로 연결하는 중")
                recreate()          // 입력칸에도 새 정보가 보이게
            }
            val ren = chip("✎") {
                val input = field(n, "새 이름", InputType.TYPE_CLASS_TEXT)
                android.app.AlertDialog.Builder(this).setTitle("이름 바꾸기")
                    .setView(FrameLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(input) })
                    .setPositiveButton("바꾸기") { _, _ ->
                        val nn = input.text.toString().trim()
                        if (nn.isEmpty() || nn == n) return@setPositiveButton
                        if (all.has(nn)) return@setPositiveButton toast("같은 이름이 이미 있어요")
                        all.put(nn, all.get(n)); all.remove(n); pr.profiles = all.toString()
                        if (pr.profileCurrent == n) pr.profileCurrent = nn
                        drawProfiles()
                    }.setNegativeButton("취소", null).show()
            }
            val del = chip("✕") {
                confirm("'$n' 프로필을 삭제할까요?", "삭제") {
                    all.remove(n); pr.profiles = all.toString()
                    if (pr.profileCurrent == n) pr.profileCurrent = ""
                    drawProfiles()
                }
            }.apply { setTextColor(0xFFFF8A8A.toInt()) }
            pfList.addView(divider())
            pfList.addView(macroRow(n, sub, use, ren, del))
        }
    }

    private val closedFolders = HashSet<String>()

    private fun pickFolder(name: String, cur: String, existing: List<String>) {
        val input = field(cur, "폴더 이름 (비우면 폴더에서 빼기)", InputType.TYPE_CLASS_TEXT)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            if (existing.isNotEmpty()) addView(LinearLayout(this@MainActivity).apply {
                for (e in existing.take(4)) addView(chip(e) { input.setText(e) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6); bottomMargin = px(8) })
            })
            addView(input)
        }
        android.app.AlertDialog.Builder(this).setTitle("'$name' 폴더 정하기").setView(box)
            .setPositiveButton("확인") { _, _ ->
                mc("folder", JSONObject().put("name", name).put("folder", input.text.toString().trim()))
            }.setNegativeButton("취소", null).show()
    }

    private fun confirm(msg: String, ok: String, action: () -> Unit) {
        android.app.AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton(ok) { _, _ -> action() }
            .setNegativeButton("취소", null).show()
    }

    private fun keyTile(label: String, which: String): Pair<LinearLayout, TextView> {
        val v = text("", 15f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1 }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(px(6), px(10), px(6), px(10))
            background = round(C.FIELD, 12)
            isClickable = true
            setOnClickListener { mc("capture", JSONObject().put("which", which)) }
            addView(text(label, 12f, C.SUB).apply { gravity = Gravity.CENTER })
            addView(v)
            addView(text("눌러서 변경", 11f, 0xFF5D6370.toInt()).apply { gravity = Gravity.CENTER; tag = "em" })
        }
        return tile to v
    }

    private fun buildMacroCard(): LinearLayout {
        mcCard = card().apply { visibility = View.GONE }
        mcTitle = text("녹화된 동작 없음", 17f, C.TEXT, bold = true)
        mcText = text("", 13f, C.SUB).apply { setPadding(0, px(2), 0, px(12)) }
        mcClear = chip("지우기") {
            confirm("지금 녹화를 지울까요? (저장한 목록은 그대로예요)", "지우기") { mc("clear") }
        }
        mcNameBtn = chip("이름") {
            val st = KvmAccessibilityService.macroState
            val cur = if (st == null || st.isNull("name")) "" else st.optString("name")
            renameMacro(cur)
        }
        mcCard.addView(LinearLayout(this).apply {
            gravity = Gravity.TOP
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(mcTitle); addView(mcText)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mcNameBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
            addView(mcClear)
        })
        fun big(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; setTextColor(C.TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), round(C.FIELD, 12, color), null)
            isClickable = true; setOnClickListener { onClick() }
        }
        mcRec = big("●  녹화", 0x66EF4444) { mcStartStop("record") }
        mcPlay = big("▶  실행", 0x6622C55E) { mcStartStop("play") }
        mcCard.addView(LinearLayout(this).apply {
            addView(mcRec, LinearLayout.LayoutParams(0, px(48), 1f).apply { marginEnd = px(5) })
            addView(mcPlay, LinearLayout.LayoutParams(0, px(48), 1f).apply { marginStart = px(5) })
        })
        // 녹화 키 · 실행 키 · 전환 키 · 반복을 한 줄 타일로
        val (tr, rk) = keyTile("녹화 키", "rec"); tileRec = tr; mcRecKey = rk
        val (tp, pk) = keyTile("실행 키", "play"); tilePlay = tp; mcPlayKey = pk
        val (ts, sk) = keyTile("전환 키", "switch"); tileSw = ts; mcSwKey = sk
        mcRepeat = text("1", 15f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER; minWidth = px(28) }
        fun setRepeat(d: Int) {
            val v = ((mcRepeat.text.toString().toIntOrNull() ?: 1) + d).coerceIn(0, 9999)
            mcRepeat.text = v.toString()
            mc("repeat", JSONObject().put("value", v))
        }
        fun mini(s: String, d: Int) = text(s, 17f, C.SUB, bold = true).apply {
            gravity = Gravity.CENTER; isClickable = true; setOnClickListener { setRepeat(d) }
        }
        val tileRep = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(px(4), px(10), px(4), px(10))
            background = round(C.FIELD, 12)
            addView(text("반복", 12f, C.SUB).apply { gravity = Gravity.CENTER })
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                addView(mini("−", -1), LinearLayout.LayoutParams(px(26), px(24)))
                addView(mcRepeat)
                addView(mini("+", 1), LinearLayout.LayoutParams(px(26), px(24)))
            })
            addView(text("0 = 계속", 11f, 0xFF5D6370.toInt()).apply { gravity = Gravity.CENTER })
        }
        mcCard.addView(LinearLayout(this).apply {
            setPadding(0, px(12), 0, 0)
            listOf(tileRec, tilePlay, tileSw, tileRep).forEachIndexed { i, t ->
                addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = px(8) })
            }
        })
        mcHint = text("", 12f, C.SUB).apply { setPadding(0, px(8), 0, px(10)) }
        mcCard.addView(mcHint)
        mcCard.addView(divider())
        mcName = field("", "지금 녹화를 이름 붙여 저장", InputType.TYPE_CLASS_TEXT)
        mcCard.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12), 0, px(4))
            addView(mcName, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("저장") {
                val st = KvmAccessibilityService.macroState
                val n = mcName.text.toString().trim().ifEmpty { st?.optString("name", "")?.takeIf { it != "null" } ?: "" }
                if (n.isEmpty()) return@button toast("이름을 입력하세요")
                mc("save", JSONObject().put("name", n))
                mcName.setText("")
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(mcName.windowToken, 0)
                toast("'$n' 저장했어요")
            }, LinearLayout.LayoutParams(px(76), px(46)).apply { marginStart = px(8) })
        })
        mcList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mcCard.addView(mcList)
        return mcCard
    }

    private fun refreshMacro() {
        val st = KvmAccessibilityService.macroState
        if (st == null) { mcCard.visibility = View.GONE; mcOff.visibility = View.VISIBLE; return }
        mcCard.visibility = View.VISIBLE; mcOff.visibility = View.GONE
        val rec = st.optBoolean("recording"); val play = st.optBoolean("playing")
        val r = st.optString("rec_name"); val p = st.optString("play_name"); val sw = st.optString("switch_name")
        val count = st.optInt("count"); val name = if (st.isNull("name")) "" else st.optString("name")
        tabs.getOrNull(1)?.text = when { rec -> "매크로 ●"; play -> "매크로 ▶"; else -> "매크로" }
        mcTitle.text = when {
            rec -> "● 녹화 중…"
            play -> "▶ 실행 중"
            count > 0 -> (if (name.isNotEmpty()) "$name · " else "새 녹화 · ") + "동작 ${count}개 · ${st.optDouble("dur")}초"
            else -> "녹화된 동작 없음"
        }
        mcTitle.setTextColor(when { rec -> C.RED; play -> C.GREEN; else -> C.TEXT })
        mcText.text = when {
            rec -> "$r 키 또는 아래 버튼으로 끝"
            play -> "$p 키 또는 아래 버튼으로 멈춤"
            count > 0 -> "$p 키로 실행 · $r 키로 새로 녹화 · $sw 키로 전환"
            else -> "녹화를 누르면 이 화면이 내려가고 원래 화면에서 기록을 시작해요"
        }
        mcClear.visibility = if (count > 0 && !rec && !play) View.VISIBLE else View.GONE
        mcNameBtn.visibility = mcClear.visibility
        mcRec.text = if (rec) "■  녹화 끝" else "●  녹화"
        mcPlay.text = if (play) "■  멈춤" else "▶  실행"
        mcRecKey.text = r; mcPlayKey.text = p; mcSwKey.text = sw
        val cap = if (st.isNull("capture")) "" else st.optString("capture")
        for ((w, tile) in listOf("rec" to tileRec, "play" to tilePlay, "switch" to tileSw)) {
            val on = cap == w
            tile.background = round(if (on) 0x332563EB else C.FIELD, 12, if (on) C.BLUE else null)
            tile.findViewWithTag<TextView>("em")?.apply {
                text = if (on) "키를 누르세요…" else "눌러서 변경"
                setTextColor(if (on) 0xFF7FA6FF.toInt() else 0xFF5D6370.toInt())
            }
        }
        mcHint.text = if (cap.isNotEmpty()) "메인 PC 키보드에서 원하는 키를 누르세요 · Esc 취소"
            else "A·B를 정해 두면 $sw 키로 둘을 번갈아 바꿔요 (안 정하면 저장한 순서대로)"
        mcRepeat.text = st.optInt("repeat", 1).toString()
        val lib = st.optJSONArray("library") ?: org.json.JSONArray()
        val key = lib.toString() + name
        if (key == mcListKey) return
        mcListKey = key
        mcList.removeAllViews()
        if (lib.length() == 0) {
            mcList.addView(text("이 기기에서 저장한 매크로가 여기에 쌓여요", 13f, C.SUB).apply { setPadding(0, px(10), 0, 0) })
            return
        }
        mcList.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10), 0, px(2))
            addView(text("저장한 매크로 ${lib.length()}개", 12f, C.SUB), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("전체 삭제", 12f, 0xFFFF8A8A.toInt(), bold = true).apply {
                setPadding(px(10), px(6), px(10), px(6))
                isClickable = true
                setOnClickListener {
                    confirm("이 기기에서 저장한 매크로 ${lib.length()}개를 모두 삭제할까요? 되돌릴 수 없어요.", "모두 삭제") { mc("delete_all") }
                }
            })
        })
        // 폴더별로 묶기 (폴더 이름 누르면 접기/펴기)
        val groups = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until lib.length()) {
            val x = lib.getJSONObject(i)
            groups.getOrPut(x.optString("folder", "")) { ArrayList() }.add(x)
        }
        val folders = groups.keys.sortedWith(compareBy<String>({ it.isEmpty() }, { it }))
        for (f in folders) {
          if (!(f.isEmpty() && folders.size == 1)) {
            val closed = f in closedFolders
            mcList.addView(divider())
            mcList.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(10), 0, px(10))
                isClickable = true
                setOnClickListener { if (!closedFolders.add(f)) closedFolders.remove(f); mcListKey = ""; refreshMacro() }
                addView(text(if (closed) "▸" else "▾", 15f, C.SUB).apply { setPadding(0, 0, px(8), 0) })
                addView(text("📁  ${f.ifEmpty { "폴더 없음" }}", 15f, C.TEXT, bold = true))
                addView(text("  ${groups[f]!!.size}개", 13f, C.SUB), LinearLayout.LayoutParams(0, -2, 1f))
            })
            if (closed) continue
          }
          for (x in groups[f]!!) {
            val n = x.optString("name")
            val slot = if (x.isNull("slot")) "" else x.optString("slot")
            val sub = text("동작 ${x.optInt("count")}개 · ${x.optDouble("dur")}초 · ${x.optString("saved")}", 12f, C.SUB)
            fun ab(s: String) = text(s, 13f, if (slot == s) Color.WHITE else C.SUB, bold = true).apply {
                gravity = Gravity.CENTER
                background = round(if (slot == s) C.BLUE else C.FIELD, 8)
                isClickable = true
                setOnClickListener { mc("slot", JSONObject().put("name", n).put("slot", s)) }
                minWidth = px(32); minHeight = px(32)
            }
            val use: View = if (n == name) text("사용 중", 12f, C.GREEN, bold = true).apply {
                setPadding(px(10), px(6), px(10), px(6)); background = round(0x1F22C55E, 8)
            } else chip("불러오기") { mc("load", JSONObject().put("name", n)) }
            val del = chip("✕") { confirm("'$n'을(를) 삭제할까요?", "삭제") { mc("delete", JSONObject().put("name", n)) } }.apply { setTextColor(0xFFFF8A8A.toInt()) }
            mcList.addView(divider())
            val ren = chip("✎") { renameMacro(n) }
            val fold = chip("📁") { pickFolder(n, x.optString("folder", ""), groups.keys.filter { it.isNotEmpty() }) }
            mcList.addView(macroRow(n, sub, ab("A"), ab("B"), use, fold, ren, del))
          }
        }
    }

    /** 메인 PC와 이 기기의 위치를 그린 작은 지도. 조작 중이면 이 기기가 빛남 */
    private class MiniMap(ctx: android.content.Context) : View(ctx) {
        var side = "left"
        var active = false
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onDraw(c: android.graphics.Canvas) {
            val d = resources.displayMetrics.density
            val mw = 104 * d; val mh = 64 * d; val ew = 92 * d; val eh = 60 * d; val gap = 12 * d
            val cx = width / 2f; val cy = height / 2f
            val m: android.graphics.RectF; val e: android.graphics.RectF
            when (side) {
                "right" -> { val l = cx - (mw + gap + ew) / 2
                    m = android.graphics.RectF(l, cy - mh / 2, l + mw, cy + mh / 2)
                    e = android.graphics.RectF(m.right + gap, cy - eh / 2, m.right + gap + ew, cy + eh / 2) }
                "top" -> { val t = cy - (eh + gap + mh) / 2
                    e = android.graphics.RectF(cx - ew / 2, t, cx + ew / 2, t + eh)
                    m = android.graphics.RectF(cx - mw / 2, e.bottom + gap, cx + mw / 2, e.bottom + gap + mh) }
                "bottom" -> { val t = cy - (mh + gap + eh) / 2
                    m = android.graphics.RectF(cx - mw / 2, t, cx + mw / 2, t + mh)
                    e = android.graphics.RectF(cx - ew / 2, m.bottom + gap, cx + ew / 2, m.bottom + gap + eh) }
                else -> { val l = cx - (ew + gap + mw) / 2
                    e = android.graphics.RectF(l, cy - eh / 2, l + ew, cy + eh / 2)
                    m = android.graphics.RectF(e.right + gap, cy - mh / 2, e.right + gap + mw, cy + mh / 2) }
            }
            val r = 12 * d
            paint.clearShadowLayer(); paint.style = android.graphics.Paint.Style.FILL
            paint.color = if (active) 0x992C5BE8.toInt() else 0xFF2C5BE8.toInt()
            c.drawRoundRect(m, r, r, paint)
            if (active) paint.setShadowLayer(22 * d, 0f, 0f, 0xAA3DDC97.toInt())
            paint.color = if (active) 0xFF16362A.toInt() else 0xFF13261F.toInt()
            c.drawRoundRect(e, r, r, paint)
            paint.clearShadowLayer()
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = (if (active) 2.5f else 1.5f) * d
            paint.color = if (active) 0xFF3DDC97.toInt() else 0x803DDC97.toInt()
            c.drawRoundRect(e, r, r, paint)
            // 맞닿은 구간
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFF5B84A.toInt()
            val g = gap / 2
            when (side) {
                "right" -> c.drawRoundRect(m.right + g - 2 * d, cy - 22 * d, m.right + g + 2 * d, cy + 22 * d, 2 * d, 2 * d, paint)
                "top" -> c.drawRoundRect(cx - 30 * d, e.bottom + g - 2 * d, cx + 30 * d, e.bottom + g + 2 * d, 2 * d, 2 * d, paint)
                "bottom" -> c.drawRoundRect(cx - 30 * d, m.bottom + g - 2 * d, cx + 30 * d, m.bottom + g + 2 * d, 2 * d, 2 * d, paint)
                else -> c.drawRoundRect(e.right + g - 2 * d, cy - 22 * d, e.right + g + 2 * d, cy + 22 * d, 2 * d, 2 * d, paint)
            }
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.textSize = 12.5f * d
            paint.isFakeBoldText = true
            paint.color = 0xFFDBE6FF.toInt()
            c.drawText("메인 PC", m.centerX(), m.centerY() + 4.5f * d, paint)
            paint.color = 0xFF8DE6BD.toInt()
            c.drawText("이 기기", e.centerX(), e.centerY() + 4.5f * d, paint)
        }
    }

    // ------------------------------------------------------------ 자동 업데이트
    private fun showUpdate() {
        val name = KvmAccessibilityService.updateName
        updateCard.visibility = if (name != null) View.VISIBLE else View.GONE
        if (name != null && !installing) {
            updateTitle.text = "새 버전 v$name"
            updateBtn.text = "업데이트"
        }
    }

    private fun startUpdate() {
        if (installing) return
        // 이 앱이 다른 앱(자기 자신)을 설치할 수 있게 한 번 허용 필요
        if (!packageManager.canRequestPackageInstalls()) {
            toast("'이 출처 허용'을 켜고 돌아와서 다시 눌러주세요")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val svc = KvmAccessibilityService.instance ?: return toast("PC와 연결된 상태에서 눌러주세요")
        installing = true
        updateBtn.text = "받는 중…"
        svc.requestApk { file ->
            if (file == null) {
                installing = false; updateBtn.text = "다시 시도"; toast("PC에서 앱을 받지 못했어요")
            } else {
                updateBtn.text = "설치 중…"
                try { install(file) } catch (e: Exception) {
                    installing = false; updateBtn.text = "다시 시도"; toast("설치 오류: ${e.message}")
                }
            }
        }
    }

    private fun install(file: java.io.File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("kvm.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL)
            val pending = PendingIntent.getActivity(this, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    // 설치 진행 상황은 이 화면으로 다시 전달됨
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != ACTION_INSTALL) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> { installing = false; toast("업데이트 완료") }
            else -> {
                installing = false
                updateBtn.text = "다시 시도"
                toast("설치되지 않았어요: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "취소됨"}")
            }
        }
    }

    // ------------------------------------------------------------ 상태
    private fun showStatus(s: String) {
        val (color, title) = when {
            KvmAccessibilityService.instance == null -> C.SUB to "꺼져 있음"
            s.startsWith("연결됨") -> C.GREEN to "연결됨"
            s.startsWith("연결 중") -> C.AMBER to "연결 중…"
            s.startsWith("서버 거부") -> C.RED to "비밀번호 확인 필요"
            else -> C.RED to "연결 안 됨"
        }
        (statusDot.background as GradientDrawable).setColor(color)
        statusText.text = title
        statusSub.text = if (KvmAccessibilityService.instance == null) "설정 단계 1번을 완료하면 자동으로 연결됩니다" else s
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, KvmAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun batteryOk(): Boolean =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @android.annotation.SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun imeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun imeSelected(): Boolean {
        val cur = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return cur.startsWith("$packageName/")
    }

    // ------------------------------------------------------------ UI 부품
    private fun px(v: Int) = (v * dp).toInt()

    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radius).toFloat()
        stroke?.let { setStroke(px(1), it) }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(C.CARD, 18)
        setPadding(px(20), px(18), px(20), px(18))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) }
    }

    private fun section(s: String) = text(s, 13f, C.SUB, bold = true).apply {
        letterSpacing = 0.05f
        setPadding(0, 0, 0, px(6))
    }

    private fun label(s: String) = text(s, 13f, C.SUB).apply { setPadding(px(2), px(12), 0, px(6)) }

    private fun field(v: String, hint: String, type: Int) = EditText(this).apply {
        setText(v); this.hint = hint; inputType = type; isSingleLine = true
        textSize = 16f; setTextColor(C.TEXT); setHintTextColor(0xFF5C626D.toInt())
        background = round(C.FIELD, 12)
        setPadding(px(14), px(12), px(14), px(12))
    }

    private fun button(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(C.BLUE, 14), null)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(C.LINE)
        layoutParams = LinearLayout.LayoutParams(-1, px(1))
    }

    private fun step(num: String, title: String, sub: String, check: () -> Boolean, onClick: () -> Unit) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(14), 0, px(14))
            background = RippleDrawable(ColorStateList.valueOf(0x14FFFFFF), null, round(Color.WHITE, 10))
            isClickable = true
            setOnClickListener { onClick() }
            val badge = text(num, 14f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
            stepRows.add(check to badge)
            addView(badge, LinearLayout.LayoutParams(px(30), px(30)).apply { marginEnd = px(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15f, C.TEXT, bold = true))
                addView(text(sub, 13f, C.SUB).apply { setPadding(0, px(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("›", 22f, C.SUB))
        }

    private fun setBadge(badge: TextView, done: Boolean) {
        badge.tag = badge.tag ?: badge.text
        badge.text = if (done) "✓" else badge.tag as CharSequence
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (done) setColor(C.GREEN) else { setColor(Color.TRANSPARENT); setStroke(px(2), 0xFF3D424D.toInt()) }
        }
        badge.setTextColor(if (done) Color.WHITE else C.SUB)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

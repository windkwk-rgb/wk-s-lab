package com.example.kvm

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * 키보드 담당. 안드로이드 키보드로 'PC 키보드 공유'를 선택해 두면
 * 윈도우에서 치는 키가 입력창에 들어갑니다. 한/영 키로 한글 조합(두벌식).
 */
class KvmIme : InputMethodService() {

    companion object {
        @Volatile var instance: KvmIme? = null

        private const val SHIFT_ROW = ")!@#$%^&*("
        private val OEM = mapOf(          // VK → (일반, Shift)
            0xBA to ";:", 0xBB to "=+", 0xBC to ",<", 0xBD to "-_", 0xBE to ".>",
            0xBF to "/?", 0xC0 to "`~", 0xDB to "[{", 0xDC to "\\|", 0xDD to "]}", 0xDE to "'\"",
            0x6A to "**", 0x6B to "++", 0x6D to "--", 0x6E to "..", 0x6F to "//",
        )
        private val SPECIAL = mapOf(
            0x09 to KeyEvent.KEYCODE_TAB, 0x2E to KeyEvent.KEYCODE_FORWARD_DEL,
            0x25 to KeyEvent.KEYCODE_DPAD_LEFT, 0x26 to KeyEvent.KEYCODE_DPAD_UP,
            0x27 to KeyEvent.KEYCODE_DPAD_RIGHT, 0x28 to KeyEvent.KEYCODE_DPAD_DOWN,
            0x24 to KeyEvent.KEYCODE_MOVE_HOME, 0x23 to KeyEvent.KEYCODE_MOVE_END,
            0x21 to KeyEvent.KEYCODE_PAGE_UP, 0x22 to KeyEvent.KEYCODE_PAGE_DOWN,
        )
    }

    private val composer = HangulComposer()
    private var hangul = false
    val isHangul get() = hangul
    val hasTextField get() = inputActive
    private var shift = false
    private var ctrl = false
    private var alt = false
    private var caps = false
    private var inputActive = false
    private var status: TextView? = null
    private var startLogs = 0
    private var lastEdit = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        val bar = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        val pill = TextView(this).apply {
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#171A21")); cornerRadius = 20 * d
            }
            setTextColor(Color.parseColor("#ECEEF1"))
            textSize = 13f
            status = this
            updateStatus()
            // 손으로 칠 때: 눌러서 원래 쓰던 키보드로 (이 키보드는 PC 키 입력 전용이라 화면 자판이 없음)
            isClickable = true
            setOnClickListener {
                val ok = Build.VERSION.SDK_INT >= 28 && switchToPreviousInputMethod()
                if (!ok) (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
            }
        }
        bar.addView(pill, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return bar
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputActive = attribute != null && attribute.inputType != InputType.TYPE_NULL
        if (attribute != null && startLogs++ < 15) KvmAccessibilityService.instance?.sendLog(
            "키보드 앱: 입력 시작 ${attribute.packageName} (입력칸 ${if (inputActive) "있음" else "없음"}, 종류 ${attribute.inputType})")
        if (!restarting) composer.reset()
    }

    override fun onFinishInput() {
        composer.reset()
        inputActive = false
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // 마우스로 다른 곳을 탭해 조합이 끊기면 상태도 초기화.
        // 단, 방금 이 키보드가 글자를 넣은 직후에 오는 알림은 무시 (예전엔 이것 때문에 조합 중인 글자가 지워져 한글이 완성되지 않았음)
        if (SystemClock.uptimeMillis() - lastEdit < 400) return
        if (candidatesStart == -1 && !composer.isEmpty()) composer.reset()
    }

    private fun updateStatus() {
        status?.text = "⌨  PC 키보드 · ${if (hangul) "한글" else "영문"}    |    손으로 칠 땐 여기를 눌러 원래 키보드로"
    }

    fun setHangul(on: Boolean) {
        commitComposition()
        hangul = on
        updateStatus()
    }

    fun resetModifiers() {
        shift = false; ctrl = false; alt = false
        commitComposition()
    }

    // ------------------------------------------------------------ 키 처리
    fun onRemoteKey(vk: Int, down: Boolean) {
        when (vk) {
            0x10, 0xA0, 0xA1 -> { shift = down; return }
            0x11, 0xA2, 0xA3 -> { ctrl = down; return }
            0x12, 0xA4, 0xA5 -> { alt = down; return }
        }
        if (!down) return
        when (vk) {
            0x15 -> { commitComposition(); hangul = !hangul; updateStatus(); return }  // 한/영
            0x14 -> { caps = !caps; return }                                            // Caps Lock
        }
        if (!inputActive) return
        val ic = currentInputConnection ?: return

        // Ctrl 조합 (복사/붙여넣기/전체선택/실행취소 등)
        if (ctrl && vk in 0x41..0x5A) {
            commitComposition()
            sendKey(KeyEvent.KEYCODE_A + (vk - 0x41))
            return
        }

        when (vk) {
            0x08 -> {                                                        // Backspace
                lastEdit = SystemClock.uptimeMillis()
                if (hangul && composer.backspace()) updateComposing() else sendKey(KeyEvent.KEYCODE_DEL)
                return
            }
            0x0D -> { commitComposition(); sendKeyChar('\n'); return }         // Enter
            0x20 -> { commitComposition(); ic.commitText(" ", 1); return }    // Space
        }
        SPECIAL[vk]?.let { commitComposition(); sendKey(it); return }

        // 글자
        if (vk in 0x41..0x5A) {
            val lower = 'a' + (vk - 0x41)
            if (hangul) {
                lastEdit = SystemClock.uptimeMillis()
                val jamo = HangulComposer.jamoFor(lower, shift) ?: return
                val done = composer.input(jamo)
                ic.beginBatchEdit()
                if (done.isNotEmpty()) ic.commitText(done, 1)        // 완성된 글자는 확정 (조합 중이던 자리를 바꿔 끼움)
                ic.setComposingText(composer.current(), 1)
                ic.endBatchEdit()
            } else {
                commitComposition()
                val upper = shift xor caps
                ic.commitText((if (upper) lower.uppercaseChar() else lower).toString(), 1)
            }
            return
        }
        val text = when {
            vk in 0x30..0x39 -> if (shift) SHIFT_ROW[vk - 0x30].toString() else ('0' + (vk - 0x30)).toString()
            vk in 0x60..0x69 -> ('0' + (vk - 0x60)).toString()                // 숫자 키패드
            else -> OEM[vk]?.let { if (shift) it[1].toString() else it[0].toString() }
        } ?: return
        commitComposition()
        ic.commitText(text, 1)
    }

    private fun commitComposition() {
        if (composer.isEmpty()) return
        lastEdit = SystemClock.uptimeMillis()
        currentInputConnection?.apply {
            beginBatchEdit()
            setComposingText(composer.current(), 1)
            finishComposingText()
            endBatchEdit()
        }
        composer.reset()
    }

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        val s = composer.current()
        ic.setComposingText(s, 1)
        if (s.isEmpty()) {
            ic.finishComposingText()
            composer.reset()
        }
    }

    private fun sendKey(code: Int) {
        val ic = currentInputConnection ?: return
        var meta = 0
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }
}

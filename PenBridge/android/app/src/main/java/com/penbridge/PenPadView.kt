package com.penbridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * 펜 입력판. 펜 이벤트를 정규화(0~1)해서 sink로 넘긴다.
 * - 손바닥/손가락은 무시(설정 시 손가락도 입력)
 * - PC 화면 비율에 맞춘 "활성 영역"을 그려서 원이 찌그러지지 않게 함
 * - 필압, 기울기, 사이드 버튼, 지우개 끝 지원
 */
@SuppressLint("ViewConstructor")
class PenPadView(
    context: Context,
    private val sink: (ByteArray) -> Unit,
    private val onCloseTap: () -> Unit,
) : View(context) {

    var fingerInput = false
    var showCursor = true
    /** 손가락 제스처 (두 손가락 탭 = 실행 취소, 끌기 = 이동, 핀치 = 확대·축소) */
    var gesturesEnabled = true

    /** 액정 타블렛 모드: 노트북 화면을 활성 영역에 그림 */
    var mirrorMode = false
        set(v) {
            field = v
            if (!v) frame = null
            layoutActive()
            invalidate()
        }
    @Volatile private var frame: Bitmap? = null
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()

    private val density = resources.displayMetrics.density
    private val active = RectF()
    private var aspect = 0f

    // 상태 표시
    private var statusText = "연결 중…"
    private var statusColor = COLOR_WAIT
    private var latencyMs = -1L
    private var everDrew = false

    // 커서
    private var cursorX = -1f
    private var cursorY = -1f
    private var cursorPressure = 0f
    private var cursorContact = false

    // 호버
    private var hoverSeen = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastX = 0f
    private var lastY = 0f
    private val goOut = Runnable { emitOut() }

    private val closeRect = RectF()

    private val bgPaint = Paint().apply { color = Color.parseColor("#141519") }
    private val areaPaint = Paint().apply { color = Color.parseColor("#1C1E24") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A4050"); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val gridPaint = Paint().apply { color = Color.parseColor("#23262E"); strokeWidth = 1f * density }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A08CFF"); style = Paint.Style.STROKE; strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C8CCD6"); textSize = 14f * density }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280"); textSize = 16f * density; textAlign = Paint.Align.CENTER
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#262931") }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA4B8"); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A08CFF") }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8CCD6"); strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND
    }

    // ───────────── 외부에서 호출 ─────────────

    /** 연결되면 3초 뒤 상태 표시를 흐리게 (그림에 방해되지 않게) */
    private var fadeAt = Long.MAX_VALUE

    fun setStatus(text: String, ok: Boolean?, latency: Long = latencyMs) {
        statusText = text
        statusColor = when (ok) { true -> COLOR_OK; false -> COLOR_ERR; null -> COLOR_WAIT }
        latencyMs = latency
        fadeAt = if (ok == true) System.currentTimeMillis() + 3000 else Long.MAX_VALUE
        invalidate()
        if (ok == true) postInvalidateDelayed(3100)
    }

    /** 백그라운드 스레드에서 호출 가능 */
    fun setFrame(bmp: Bitmap) {
        frame = bmp
        postInvalidateOnAnimation()
    }

    fun setLatency(ms: Long) {
        latencyMs = ms
        invalidate()
    }

    fun setAspect(a: Float) {
        aspect = a
        layoutActive()
        invalidate()
    }

    /** 앱이 가려질 때 펜을 확실히 떼게 함 */
    fun releasePen() {
        handler.removeCallbacks(goOut)
        emitOut()
    }

    // ───────────── 레이아웃 ─────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutActive()
        val s = 44 * density
        closeRect.set(w - s - 12 * density, 12 * density, w - 12 * density, 12 * density + s)
    }

    private fun layoutActive() {
        val margin = (if (mirrorMode) 8 else 16) * density
        val top = (if (mirrorMode) 8 else 64) * density   // 상태 표시줄 자리 (화면 보기 중엔 겹쳐서 최대한 넓게)
        val availW = width - margin * 2
        val availH = height - top - margin
        if (availW <= 0 || availH <= 0) return
        if (aspect <= 0f) {
            active.set(margin, top, margin + availW, top + availH)
        } else {
            var w = availW
            var h = w / aspect
            if (h > availH) { h = availH; w = h * aspect }
            val left = (width - w) / 2
            val t = top + (availH - h) / 2
            active.set(left, t, left + w, t + h)
        }
        clip.reset()
        clip.addRoundRect(active, 10 * density, 10 * density, Path.Direction.CW)
    }

    // ───────────── 그리기 ─────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 활성 영역
        val r = 14 * density
        val f = frame
        if (mirrorMode && f != null && !f.isRecycled) {
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(f, null, active, framePaint)
            canvas.restore()
        } else {
            canvas.drawRoundRect(active, r, r, areaPaint)
            for (i in 1..3) {
                val x = active.left + active.width() * i / 4
                val y = active.top + active.height() * i / 4
                canvas.drawLine(x, active.top, x, active.bottom, gridPaint)
                canvas.drawLine(active.left, y, active.right, y, gridPaint)
            }
            canvas.drawRoundRect(active, r, r, borderPaint)
            drawCorners(canvas)
            if (mirrorMode) {
                canvas.drawText("노트북 화면을 불러오는 중…", active.centerX(), active.centerY(), hintPaint)
            }
        }

        if (!everDrew && !mirrorMode) {
            canvas.drawText("이 영역 = 노트북 화면", active.centerX(), active.centerY() - 6 * density, hintPaint)
            canvas.drawText("펜으로 그리면 노트북에 그대로 입력돼요", active.centerX(), active.centerY() + 18 * density, hintPaint)
        }

        // 상태 알약
        val label = if (latencyMs >= 0 && statusColor == COLOR_OK) "$statusText · ${latencyMs}ms" else statusText
        val padH = 14 * density
        val pillW = textPaint.measureText(label) + padH * 2 + 14 * density
        val pill = RectF(16 * density, 14 * density, 16 * density + pillW, 14 * density + 36 * density)
        val faded = System.currentTimeMillis() >= fadeAt
        val alpha = if (faded) 90 else 255
        pillPaint.alpha = alpha
        textPaint.alpha = alpha
        canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, pillPaint)
        dotPaint.color = statusColor
        dotPaint.alpha = alpha
        canvas.drawCircle(pill.left + padH, pill.centerY(), 5 * density, dotPaint)
        canvas.drawText(label, pill.left + padH + 12 * density, pill.centerY() + 5 * density, textPaint)
        pillPaint.alpha = 255
        textPaint.alpha = 255

        // 닫기 버튼 (화면 보기 중엔 흐리게)
        pillPaint.alpha = if (mirrorMode) 150 else 255
        canvas.drawRoundRect(closeRect, closeRect.height() / 2, closeRect.height() / 2, pillPaint)
        pillPaint.alpha = 255
        val c = 8 * density
        canvas.drawLine(closeRect.centerX() - c, closeRect.centerY() - c, closeRect.centerX() + c, closeRect.centerY() + c, closePaint)
        canvas.drawLine(closeRect.centerX() + c, closeRect.centerY() - c, closeRect.centerX() - c, closeRect.centerY() + c, closePaint)

        // 제스처 알림 (예: "실행 취소")
        val flash = flashText
        if (flash != null && SystemClock.uptimeMillis() < flashUntil) {
            val tw = textPaint.measureText(flash) + 36 * density
            val fr = RectF(width / 2f - tw / 2, height - 84 * density, width / 2f + tw / 2, height - 44 * density)
            canvas.drawRoundRect(fr, fr.height() / 2, fr.height() / 2, pillPaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(flash, fr.centerX(), fr.centerY() + 5 * density, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        // 커서
        if (showCursor && cursorX >= 0) {
            if (cursorContact) {
                canvas.drawCircle(cursorX, cursorY, (3 + 9 * cursorPressure) * density, cursorFill)
            } else {
                canvas.drawCircle(cursorX, cursorY, 7 * density, cursorRing)
            }
        }
    }

    private fun drawCorners(canvas: Canvas) {
        val l = 22 * density
        val a = active
        canvas.drawLine(a.left, a.top + l, a.left, a.top, cornerPaint); canvas.drawLine(a.left, a.top, a.left + l, a.top, cornerPaint)
        canvas.drawLine(a.right - l, a.top, a.right, a.top, cornerPaint); canvas.drawLine(a.right, a.top, a.right, a.top + l, cornerPaint)
        canvas.drawLine(a.left, a.bottom - l, a.left, a.bottom, cornerPaint); canvas.drawLine(a.left, a.bottom, a.left + l, a.bottom, cornerPaint)
        canvas.drawLine(a.right - l, a.bottom, a.right, a.bottom, cornerPaint); canvas.drawLine(a.right, a.bottom, a.right, a.bottom - l, cornerPaint)
    }

    // ───────────── 입력 처리 ─────────────

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER ||
            (fingerInput && t == MotionEvent.TOOL_TYPE_FINGER)
    }

    /** 이벤트 안에서 펜 포인터 위치. 전용 펜을 손가락보다 우선. 없으면 -1 */
    private fun penIndex(e: MotionEvent): Int {
        for (i in 0 until e.pointerCount) {
            val t = e.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) return i
        }
        if (fingerInput) for (i in 0 until e.pointerCount) if (isPen(e, i)) return i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 닫기 버튼은 손가락으로도 누를 수 있게
        if (e.actionMasked == MotionEvent.ACTION_DOWN && closeRect.contains(e.x, e.y)) {
            onCloseTap()
            return true
        }

        val idx = penIndex(e)
        if (idx < 0) {
            // 손가락만 닿음 → 제스처 (펜은 그대로 그리기)
            if (gesturesEnabled && !fingerInput) handleFingers(e)
            return true
        }
        if (e.getToolType(idx) != MotionEvent.TOOL_TYPE_FINGER) penNearUntil = SystemClock.uptimeMillis() + 500

        val action = e.actionMasked
        val isThisPointer = when (action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> e.actionIndex == idx
            else -> true
        }

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (isThisPointer) {
                handler.removeCallbacks(goOut)
                requestUnbufferedDispatch(e)   // 입력 지연 최소화
                everDrew = true
                emit(Protocol.STATE_CONTACT, e, idx, -1)
            }
            MotionEvent.ACTION_MOVE -> {
                handler.removeCallbacks(goOut)
                for (h in 0 until e.historySize) emit(Protocol.STATE_CONTACT, e, idx, h)
                emit(Protocol.STATE_CONTACT, e, idx, -1)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (isThisPointer) {
                emit(Protocol.STATE_HOVER, e, idx, -1)
                // 호버를 지원하지 않는 펜/손가락이면 잠시 뒤 "범위 밖"으로
                if (!hoverSeen || e.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER) {
                    handler.postDelayed(goOut, 120)
                }
            }
            MotionEvent.ACTION_CANCEL -> emitOut()
        }
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val t = e.getToolType(0)
        if (t != MotionEvent.TOOL_TYPE_STYLUS && t != MotionEvent.TOOL_TYPE_ERASER) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverSeen = true
                penNearUntil = SystemClock.uptimeMillis() + 500
                handler.removeCallbacks(goOut)
                for (h in 0 until e.historySize) emit(Protocol.STATE_HOVER, e, 0, h)
                emit(Protocol.STATE_HOVER, e, 0, -1)
            }
            // 안드로이드는 펜이 화면에 닿기 직전에도 HOVER_EXIT를 보냄 → 조금 기다렸다가 처리
            MotionEvent.ACTION_HOVER_EXIT -> handler.postDelayed(goOut, 80)
        }
        return true
    }

    // ───────────── 손가락 제스처 ─────────────

    private var penNearUntil = 0L
    private var gActive = false
    private var gInvalid = false
    private var gMoved = false
    private var gMode = 0          // 1 이동, 2 확대·축소
    private var gStart = 0L
    private var gMax = 0
    private var gStartCx = 0f
    private var gStartCy = 0f
    private var gStartSpan = 0f
    private var gLastCx = 0f
    private var gLastCy = 0f
    private var gLastSpan = 0f
    private val slop = 14 * density
    private val palmSize = 42 * density
    private var flashText: String? = null
    private var flashUntil = 0L

    /** 손가락들의 중심과 평균 거리. skip = 막 뗀 손가락 */
    private fun centroid(e: MotionEvent, skip: Int = -1): Triple<Float, Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        if (n == 0) return Triple(0f, 0f, 0f)
        val cx = sx / n
        val cy = sy / n
        var span = 0f
        for (i in 0 until e.pointerCount) if (i != skip) span += hypot(e.getX(i) - cx, e.getY(i) - cy)
        return Triple(cx, cy, if (n >= 2) span / n else 0f)
    }

    private fun baseline(e: MotionEvent, skip: Int = -1) {
        val (cx, cy, span) = centroid(e, skip)
        gLastCx = cx; gLastCy = cy; gLastSpan = span
        if (!gMoved) { gStartCx = cx; gStartCy = cy; gStartSpan = span }
    }

    private fun handleFingers(e: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gActive = true
                gInvalid = now < penNearUntil
                gMoved = false
                gMode = 0
                gStart = now
                gMax = 1
                baseline(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gMax = max(gMax, e.pointerCount)
                baseline(e)
            }
            MotionEvent.ACTION_POINTER_UP -> baseline(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                if (!gActive || gInvalid) return
                if (now < penNearUntil) { gInvalid = true; return }
                for (i in 0 until e.pointerCount) {
                    if (e.getTouchMajor(i) > palmSize) { gInvalid = true; return }   // 손바닥
                }
                val (cx, cy, span) = centroid(e)
                if (e.pointerCount < 2) {
                    if (hypot(cx - gStartCx, cy - gStartCy) > slop) gMoved = true   // 한 손가락 끌기 → 탭 아님
                    return
                }
                if (!gMoved) {
                    val ratio = if (gStartSpan > 0f) span / gStartSpan else 1f
                    when {
                        abs(ratio - 1f) > 0.08f -> { gMoved = true; gMode = 2 }
                        hypot(cx - gStartCx, cy - gStartCy) > slop -> { gMoved = true; gMode = 1 }
                    }
                    if (gMoved) { gLastCx = cx; gLastCy = cy; gLastSpan = span }
                    return
                }
                if (gMode == 1) {
                    val dx = (cx - gLastCx) / density
                    val dy = (cy - gLastCy) / density
                    if (dx != 0f || dy != 0f) sink(Protocol.pan(dx, dy))
                } else if (gMode == 2 && gLastSpan > 0f && span > 0f) {
                    sink(Protocol.zoom(span / gLastSpan))
                }
                gLastCx = cx; gLastCy = cy; gLastSpan = span
            }
            MotionEvent.ACTION_UP -> {
                if (gActive && !gInvalid && !gMoved && now - gStart < 350 && gMax in 2..3) {
                    val id = if (gMax == 2) Protocol.A_UNDO else Protocol.A_REDO
                    sink(Protocol.action(id, true))
                    sink(Protocol.action(id, false))
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    flash(if (gMax == 2) "실행 취소" else "다시 실행")
                }
                gActive = false
            }
            MotionEvent.ACTION_CANCEL -> gActive = false
        }
    }

    /** 화면 아래에 잠깐 문구 표시 */
    fun flash(text: String) {
        flashText = text
        flashUntil = SystemClock.uptimeMillis() + 700
        invalidate()
        postInvalidateDelayed(750)
    }

    private fun emit(state: Int, e: MotionEvent, i: Int, h: Int) {
        val x = if (h < 0) e.getX(i) else e.getHistoricalX(i, h)
        val y = if (h < 0) e.getY(i) else e.getHistoricalY(i, h)
        val tool = e.getToolType(i)

        var pressure = if (h < 0) e.getPressure(i) else e.getHistoricalPressure(i, h)
        if (tool == MotionEvent.TOOL_TYPE_FINGER) pressure = FINGER_PRESSURE
        pressure = pressure.coerceIn(0f, 1f)

        // 안드로이드: 기울기(수직에서 벌어진 각) + 방향 → Windows: X/Y 기울기(도)
        val tilt = (if (h < 0) e.getAxisValue(MotionEvent.AXIS_TILT, i)
                    else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h)).coerceIn(0f, 1.48f)
        val orient = if (h < 0) e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i)
                     else e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i, h)
        val t = tan(tilt)
        val tiltX = Math.toDegrees(atan(t * sin(orient)).toDouble()).toFloat()
        val tiltY = -Math.toDegrees(atan(t * cos(orient)).toDouble()).toFloat()

        var buttons = 0
        if (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) buttons = buttons or Protocol.BTN_BARREL
        if (e.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) buttons = buttons or Protocol.BTN_SECONDARY
        if (tool == MotionEvent.TOOL_TYPE_ERASER) buttons = buttons or Protocol.BTN_ERASER

        val nx = if (active.width() > 0) (x - active.left) / active.width() else 0f
        val ny = if (active.height() > 0) (y - active.top) / active.height() else 0f
        sink(Protocol.pen(state, buttons, nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f), pressure, tiltX, tiltY))

        lastX = x
        lastY = y
        if (h < 0) {
            cursorX = x.coerceIn(active.left, active.right)
            cursorY = y.coerceIn(active.top, active.bottom)
            cursorPressure = pressure
            cursorContact = state == Protocol.STATE_CONTACT
            if (showCursor) invalidate()
        }
    }

    private fun emitOut() {
        if (cursorX < 0) return
        sink(Protocol.pen(Protocol.STATE_OUT, 0, 0f, 0f, 0f, 0f, 0f))
        cursorX = -1f
        cursorContact = false
        invalidate()
    }

    companion object {
        private val COLOR_OK = Color.parseColor("#3CCF88")
        private val COLOR_WAIT = Color.parseColor("#D29922")
        private val COLOR_ERR = Color.parseColor("#E5534B")
        private const val FINGER_PRESSURE = 0.7f
    }
}

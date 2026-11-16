package com.penbridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 펜 입력판. 펜 이벤트를 정규화(0~1)해서 sink로 넘긴다.
 * - 손바닥/손가락은 무시(설정 시 손가락도 입력)
 * - PC 화면 비율에 맞춘 "활성 영역"을 그려서 원이 찌그러지지 않게 함
 * - 필압, 기울기, 사이드 버튼, 지우개 끝 지원
 * - 그리기 영역 크기·위치 조절: 좌표는 항상 "영역 기준 0~1"이라 노트북 쪽은 바뀌는 것이 없음.
 *   영역 밖은 손을 올려 두는 자리 (펜·손가락 모두 무시)
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

    // 그리기 영역 크기·위치
    private val avail = RectF()      // 영역을 놓을 수 있는 범위
    private var fullW = 0f           // 100%일 때 크기 (노트북 화면 비율)
    private var fullH = 0f
    var areaScale = 1f; private set
    var areaX = 0.5f; private set
    var areaY = 0.5f; private set
    /** 영역 조절을 끝냈을 때 (크기, 가로 위치, 세로 위치) → 저장용 */
    var onAreaChanged: ((Float, Float, Float) -> Unit)? = null
    /** 그리는 중에 안드로이드가 펜 입력을 취소했을 때 (보통 손바닥이 닿아서) */
    var onPenCut: (() -> Unit)? = null
    /** 툴바를 끈 경우 입력판 위에 '영역' 버튼을 보여 줌 */
    var showAreaButton = false
    private val areaBtnRect = RectF()
    private var penIgnored = false   // 영역 밖에서 시작한 펜 → 뗄 때까지 무시
    private val edgeTol = 24 * density

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

    /** 영역 크기(0.35~1)와 위치(0~1). 연결에는 영향 없음 */
    fun setArea(scale: Float, x: Float, y: Float) {
        areaScale = scale.coerceIn(MIN_AREA, 1f)
        areaX = x.coerceIn(0f, 1f)
        areaY = y.coerceIn(0f, 1f)
        layoutActive()
        invalidate()
    }

    // ───────────── 레이아웃 ─────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutActive()
        val s = 44 * density
        closeRect.set(w - s - 12 * density, 12 * density, w - 12 * density, 12 * density + s)
        areaBtnRect.set(closeRect.left - s - 10 * density, closeRect.top, closeRect.left - 10 * density, closeRect.bottom)
    }

    private fun layoutActive() {
        val margin = (if (mirrorMode) 8 else 16) * density
        val top = (if (mirrorMode) 8 else 64) * density   // 상태 표시줄 자리 (화면 보기 중엔 겹쳐서 최대한 넓게)
        val availW = width - margin * 2
        val availH = height - top - margin
        if (availW <= 0 || availH <= 0) return
        avail.set(margin, top, margin + availW, top + availH)
        if (aspect <= 0f) {
            fullW = availW; fullH = availH
        } else {
            fullW = availW
            fullH = fullW / aspect
            if (fullH > availH) { fullH = availH; fullW = fullH * aspect }
        }
        val w = fullW * areaScale
        val h = fullH * areaScale
        val left = avail.left + (availW - w) * areaX
        val t = avail.top + (availH - h) * areaY
        active.set(left, t, left + w, t + h)
        clip.reset()
        clip.addRoundRect(active, 10 * density, 10 * density, Path.Direction.CW)
        // 화면 가장자리를 쓸면 나오는 '뒤로 가기' 같은 시스템 동작이 그리는 선을 끊지 않게
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val r = Rect()
            active.roundOut(r)
            systemGestureExclusionRects = listOf(r)
        }
    }

    private fun farOutside(x: Float, y: Float): Boolean =
        x < active.left - edgeTol || x > active.right + edgeTol || y < active.top - edgeTol || y > active.bottom + edgeTol

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

        if (!everDrew && !mirrorMode && !editingArea) {
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

        // 툴바가 없을 때 '영역' 버튼
        if (showAreaButton && !editingArea) {
            canvas.drawRoundRect(areaBtnRect, areaBtnRect.height() / 2, areaBtnRect.height() / 2, pillPaint)
            val q = 8 * density
            val b = RectF(areaBtnRect.centerX() - q, areaBtnRect.centerY() - q * 0.75f, areaBtnRect.centerX() + q, areaBtnRect.centerY() + q * 0.75f)
            closePaint.style = Paint.Style.STROKE
            canvas.drawRoundRect(b, 2 * density, 2 * density, closePaint)
            closePaint.style = Paint.Style.FILL
        }

        if (editingArea) drawAreaEditor(canvas)

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
        if (editingArea) { handleAreaEdit(e); return true }
        // 닫기 버튼은 손가락으로도 누를 수 있게
        if (e.actionMasked == MotionEvent.ACTION_DOWN && closeRect.contains(e.x, e.y)) {
            onCloseTap()
            return true
        }
        if (showAreaButton && e.actionMasked == MotionEvent.ACTION_DOWN && areaBtnRect.contains(e.x, e.y)) {
            startAreaEdit()
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
                // 영역 밖(손 올려 두는 곳)에서 펜을 대면 뗄 때까지 무시
                if (farOutside(e.getX(idx), e.getY(idx))) {
                    penIgnored = true
                    emitOut()
                    return true
                }
                penIgnored = false
                requestUnbufferedDispatch(e)   // 입력 지연 최소화
                everDrew = true
                emit(Protocol.STATE_CONTACT, e, idx, -1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (penIgnored) return true
                handler.removeCallbacks(goOut)
                for (h in 0 until e.historySize) emit(Protocol.STATE_CONTACT, e, idx, h)
                emit(Protocol.STATE_CONTACT, e, idx, -1)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (isThisPointer) {
                if (penIgnored) { penIgnored = false; return true }
                emit(Protocol.STATE_HOVER, e, idx, -1)
                // 호버를 지원하지 않는 펜/손가락이면 잠시 뒤 "범위 밖"으로
                if (!hoverSeen || e.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER) {
                    handler.postDelayed(goOut, 120)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                // 안드로이드가 그리던 펜을 취소함 (대개 손바닥이 다른 입력으로 닿았을 때)
                val wasDrawing = cursorContact && !penIgnored
                penIgnored = false
                emitOut()
                if (wasDrawing) {
                    flash("펜 입력이 끊겼어요 · 손이 화면에 닿았나요?")
                    onPenCut?.invoke()
                }
            }
        }
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val t = e.getToolType(0)
        if (t != MotionEvent.TOOL_TYPE_STYLUS && t != MotionEvent.TOOL_TYPE_ERASER) return super.onHoverEvent(e)
        if (editingArea) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverSeen = true
                if (farOutside(e.x, e.y)) {   // 영역 밖 → 노트북에서도 펜이 멀어진 것으로
                    handler.removeCallbacks(goOut)
                    emitOut()
                    return true
                }
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
                gInvalid = now < penNearUntil || !active.contains(e.x, e.y)   // 영역 밖 = 손 올려 두는 곳
                gMoved = false
                gMode = 0
                gStart = now
                gMax = 1
                baseline(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!active.contains(e.getX(e.actionIndex), e.getY(e.actionIndex))) gInvalid = true
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

    // ───────────── 그리기 영역 조절 ─────────────

    var editingArea = false
        private set
    private var editDrag = 0          // 1 옮기기, 2 크기 조절
    private var anchorX = 0f          // 크기 조절 중 고정되는 반대쪽 모서리
    private var anchorY = 0f
    private var anchorRight = false
    private var anchorBottom = false
    private var dragX = 0f
    private var dragY = 0f
    private val btnLabels = arrayOf("−", "+", "가운데", "화면 가득", "완료")
    private val btnRects = Array(btnLabels.size) { RectF() }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A08CFF") }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val doneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5B45E6") }
    private val outsideText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B5160"); textSize = 13f * density; textAlign = Paint.Align.CENTER
    }

    /** 조절 화면 열기. 이 동안 펜 입력은 노트북으로 보내지 않음 (연결은 그대로 유지) */
    fun startAreaEdit() {
        if (editingArea) return
        releasePen()
        editingArea = true
        editDrag = 0
        invalidate()
    }

    fun finishAreaEdit() {
        if (!editingArea) return
        editingArea = false
        editDrag = 0
        onAreaChanged?.invoke(areaScale, areaX, areaY)
        flash("그리기 영역 ${(areaScale * 100).roundToInt()}%")
        invalidate()
    }

    private fun handleAreaEdit(e: MotionEvent) {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                editDrag = 0
                for (i in btnRects.indices) if (btnRects[i].contains(x, y)) { onAreaButton(i); return }
                val grab = 40 * density
                val cx = floatArrayOf(active.left, active.right, active.left, active.right)
                val cy = floatArrayOf(active.top, active.top, active.bottom, active.bottom)
                for (i in 0 until 4) {
                    if (hypot(x - cx[i], y - cy[i]) < grab) {
                        anchorRight = i % 2 == 0      // 왼쪽 모서리를 잡으면 오른쪽이 고정
                        anchorBottom = i < 2          // 위쪽 모서리를 잡으면 아래쪽이 고정
                        anchorX = if (anchorRight) active.right else active.left
                        anchorY = if (anchorBottom) active.bottom else active.top
                        editDrag = 2
                        return
                    }
                }
                if (active.contains(x, y)) { editDrag = 1; dragX = x; dragY = y }
            }
            MotionEvent.ACTION_MOVE -> when (editDrag) {
                1 -> {
                    placeAt(active.left + (x - dragX), active.top + (y - dragY))
                    dragX = x
                    dragY = y
                }
                2 -> if (fullW > 0f && fullH > 0f) {
                    val ratio = fullW / fullH
                    val wNew = max(abs(x - anchorX), abs(y - anchorY) * ratio)
                    areaScale = (wNew / fullW).coerceIn(MIN_AREA, 1f)
                    val w = fullW * areaScale
                    val h = fullH * areaScale
                    placeAt(if (anchorRight) anchorX - w else anchorX, if (anchorBottom) anchorY - h else anchorY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> editDrag = 0
        }
    }

    /** 영역 왼쪽 위를 (left, top)에 두고 화면 안으로 맞춤 */
    private fun placeAt(left: Float, top: Float) {
        val freeW = avail.width() - fullW * areaScale
        val freeH = avail.height() - fullH * areaScale
        areaX = if (freeW > 1f) ((left - avail.left) / freeW).coerceIn(0f, 1f) else 0.5f
        areaY = if (freeH > 1f) ((top - avail.top) / freeH).coerceIn(0f, 1f) else 0.5f
        layoutActive()
        invalidate()
    }

    private fun onAreaButton(i: Int) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (i) {
            0 -> setArea((areaScale * 10 - 1).roundToInt() / 10f, areaX, areaY)
            1 -> setArea((areaScale * 10 + 1).roundToInt() / 10f, areaX, areaY)
            2 -> setArea(areaScale, 0.5f, 0.5f)
            3 -> setArea(1f, 0.5f, 0.5f)
            4 -> finishAreaEdit()
        }
    }

    private fun drawAreaEditor(canvas: Canvas) {
        val r = 14 * density
        canvas.drawRoundRect(active, r, r, cornerPaint)
        val hr = 11 * density
        for ((hx, hy) in listOf(active.left to active.top, active.right to active.top,
                                active.left to active.bottom, active.right to active.bottom)) {
            canvas.drawCircle(hx, hy, hr, handleFill)
            canvas.drawCircle(hx, hy, hr, handleRing)
        }
        canvas.drawText("그리기 영역 ${(areaScale * 100).roundToInt()}%", active.centerX(), active.centerY() - 8 * density, hintPaint)
        canvas.drawText("가운데를 끌면 이동 · 모서리를 끌면 크기 조절", active.centerX(), active.centerY() + 18 * density, outsideText)
        if (areaScale < 0.98f) {
            // 영역 밖 안내 (가장 넓은 빈 곳에)
            val gaps = listOf(
                RectF(avail.left, avail.top, active.left, avail.bottom), RectF(active.right, avail.top, avail.right, avail.bottom),
                RectF(avail.left, avail.top, avail.right, active.top), RectF(avail.left, active.bottom, avail.right, avail.bottom),
            )
            val g = gaps.maxByOrNull { it.width() * it.height() }
            if (g != null && g.width() > 90 * density && g.height() > 40 * density) {
                canvas.drawText("손 올려 두는 곳", g.centerX(), g.centerY(), outsideText)
            }
        }

        // 아래쪽 버튼
        val bh = 48 * density
        val gap = 8 * density
        val widths = btnLabels.map { max(56 * density, textPaint.measureText(it) + 36 * density) }
        var bx = width / 2f - (widths.sum() + gap * (widths.size - 1)) / 2f
        val by = height - 24 * density - bh
        textPaint.textAlign = Paint.Align.CENTER
        for (i in btnLabels.indices) {
            val rect = btnRects[i]
            rect.set(bx, by, bx + widths[i], by + bh)
            canvas.drawRoundRect(rect, bh / 2, bh / 2, if (i == btnLabels.lastIndex) doneFill else pillPaint)
            canvas.drawText(btnLabels[i], rect.centerX(), rect.centerY() + 5 * density, textPaint)
            bx += widths[i] + gap
        }
        textPaint.textAlign = Paint.Align.LEFT
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
        /** 그리기 영역 최소 크기 (35%) */
        private const val MIN_AREA = 0.35f
    }
}

package com.penbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 툴바 상태 (PadActivity가 관리) */
data class ToolbarState(
    val eraser: Boolean = false,
    val mirror: Boolean = false,
    val collapsed: Boolean = false,
    val right: Boolean = false,
)

private val Bar = Color(0xFF1B1D23)
private val Ink = Color(0xFFC9CCD6)
private val Faint = Color(0xFF3A3D47)
private val Violet = Color(0xFF7A63FF)

/**
 * 입력판 옆 세로 툴바.
 * - 버튼은 48dp(손가락으로 누르기 편한 크기), 누르면 진동 + 입력판 아래에 이름이 잠깐 뜸
 * - 실행 취소/다시 실행/브러시 크기/확대·축소는 꾹 누르면 반복
 * - 손 도구는 누르고 있는 동안만 (캔버스 이동)
 * - 접으면 얇은 손잡이만 남음
 */
@Composable
fun PadToolbar(
    state: ToolbarState,
    onAction: (id: Int, down: Boolean, label: String) -> Unit,
    onMirror: () -> Unit,
    onCollapse: () -> Unit,
) {
    if (state.collapsed) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(22.dp)
                .background(Bar)
                .pointerInput(Unit) { detectTapGestures(onTap = { onCollapse() }) }
                .semantics { contentDescription = "툴바 펼치기"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(4.dp).height(44.dp).clip(RoundedCornerShape(2.dp)).background(Faint))
        }
        return
    }

    val undo = rememberVectorPainter(Icons.AutoMirrored.Rounded.Undo)
    val redo = rememberVectorPainter(Icons.AutoMirrored.Rounded.Redo)
    val eraser = painterResource(R.drawable.ic_eraser)
    val minus = rememberVectorPainter(Icons.Rounded.RemoveCircleOutline)
    val plus = rememberVectorPainter(Icons.Rounded.AddCircleOutline)
    val hand = rememberVectorPainter(Icons.Rounded.PanTool)
    val zoomIn = rememberVectorPainter(Icons.Rounded.ZoomIn)
    val zoomOut = rememberVectorPainter(Icons.Rounded.ZoomOut)
    val fit = rememberVectorPainter(Icons.Rounded.FitScreen)
    val save = rememberVectorPainter(Icons.Rounded.Save)
    val screen = rememberVectorPainter(Icons.Rounded.ScreenShare)
    val fold = rememberVectorPainter(
        if (state.right) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
    )

    Column(
        Modifier
            .fillMaxHeight()
            .width(64.dp)
            .background(Bar)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolButton(fold, "툴바 접기", small = true) { if (it) onCollapse() }
        Gap()
        ToolButton(undo, "실행 취소", repeat = true) { onAction(Protocol.A_UNDO, it, "실행 취소") }
        ToolButton(redo, "다시 실행", repeat = true) { onAction(Protocol.A_REDO, it, "다시 실행") }
        Gap()
        ToolButton(eraser, if (state.eraser) "펜으로" else "지우개", active = state.eraser) {
            onAction(Protocol.A_ERASER, it, if (state.eraser) "펜" else "지우개")
        }
        ToolButton(minus, "브러시 작게", repeat = true) { onAction(Protocol.A_SIZE_DOWN, it, "브러시 작게") }
        ToolButton(plus, "브러시 크게", repeat = true) { onAction(Protocol.A_SIZE_UP, it, "브러시 크게") }
        Gap()
        ToolButton(hand, "손 도구 (누르는 동안 이동)") { onAction(Protocol.A_HAND, it, "손 도구") }
        ToolButton(zoomIn, "확대", repeat = true) { onAction(Protocol.A_ZOOM_IN, it, "확대") }
        ToolButton(zoomOut, "축소", repeat = true) { onAction(Protocol.A_ZOOM_OUT, it, "축소") }
        ToolButton(fit, "화면에 맞춤") { onAction(Protocol.A_FIT, it, "화면에 맞춤") }
        Gap()
        ToolButton(save, "저장") { onAction(Protocol.A_SAVE, it, "저장") }
        Spacer(Modifier.height(18.dp))
        ToolButton(screen, if (state.mirror) "노트북 화면 끄기" else "노트북 화면 보기", active = state.mirror) {
            if (it) onMirror()
        }
    }
}

@Composable
private fun Gap() {
    Box(Modifier.padding(vertical = 4.dp).width(28.dp).height(1.dp).background(Faint))
}

@Composable
private fun ToolButton(
    icon: Painter,
    label: String,
    active: Boolean = false,
    repeat: Boolean = false,
    small: Boolean = false,
    onPress: (down: Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        active -> Violet
        pressed -> Color(0x26FFFFFF)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(if (small) 40.dp else 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(label, repeat) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    press(true)
                    var job: Job? = null
                    if (repeat) {
                        job = scope.launch {
                            delay(420)
                            while (isActive) {
                                press(true)
                                delay(110)
                            }
                        }
                    }
                    tryAwaitRelease()
                    job?.cancel()
                    pressed = false
                    press(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) Color.White else Ink,
            modifier = Modifier.size(if (small) 22.dp else 24.dp),
        )
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.penbridge

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 첫 화면: 저장된 노트북, 새 연결(USB / Wi‑Fi), 설정 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private val saved = mutableStateOf<List<SavedPc>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        saved.value = prefs.savedPcs

        // 노트북이 USB로 업데이트를 설치한 뒤 다시 띄웠거나, '앱 열면 자동 연결'이 켜져 있으면 바로 연결
        if (savedInstanceState == null) {
            val forced = intent.getBooleanExtra(EXTRA_AUTOCONNECT, false)
            if (forced || prefs.autoConnect) {
                prefs.lastHost?.let { open(it, prefs.lastName) }
            }
        }

        setContent {
            PenBridgeTheme {
                HomeScreen(
                    saved = saved.value,
                    prefs = prefs,
                    version = AppUpdater.versionString(AppUpdater.versionCode(this)),
                    onOpen = ::open,
                    onForget = { pc ->
                        prefs.forgetPc(pc.host)
                        saved.value = prefs.savedPcs
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        saved.value = prefs.savedPcs
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOCONNECT, false)) prefs.lastHost?.let { open(it, prefs.lastName) }
    }

    private fun open(host: String, name: String?) {
        prefs.lastHost = host
        prefs.lastName = name
        startActivity(
            Intent(this, PadActivity::class.java)
                .putExtra(PadActivity.EXTRA_HOST, host)
                .putExtra(PadActivity.EXTRA_NAME, name),
        )
    }

    companion object {
        const val EXTRA_AUTOCONNECT = "autoconnect"
    }
}

// ───────────────────────── 테마 ─────────────────────────

private val Brand = Color(0xFF5B45E6)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E0FF),
    onPrimaryContainer = Color(0xFF22127A),
    secondaryContainer = Color(0xFFE9E6F7),
    onSecondaryContainer = Color(0xFF1B2540),
    background = Color(0xFFF3F4F7),
    surface = Color(0xFFF3F4F7),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF7F8FA),
    onSurface = Color(0xFF161922),
    onSurfaceVariant = Color(0xFF697080),
    outlineVariant = Color(0x1A0F1423),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA08CFF),
    onPrimary = Color(0xFF17123A),
    primaryContainer = Color(0xFF2E2660),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondaryContainer = Color(0xFF2B3040),
    onSecondaryContainer = Color(0xFFDCE2F5),
    background = Color(0xFF141519),
    surface = Color(0xFF141519),
    surfaceContainer = Color(0xFF1F2127),
    surfaceContainerHigh = Color(0xFF262931),
    onSurface = Color(0xFFECEEF3),
    onSurfaceVariant = Color(0xFF9BA1AE),
    outlineVariant = Color(0x14FFFFFF),
)

@Composable
fun PenBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

// ───────────────────────── 화면 ─────────────────────────

@Composable
private fun HomeScreen(
    saved: List<SavedPc>,
    prefs: Prefs,
    version: String,
    onOpen: (String, String?) -> Unit,
    onForget: (SavedPc) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Discovery.Found>?>(null) }
    var manualOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }

    fun search() {
        if (searching) return
        searching = true
        found = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Discovery.search(context) } catch (_: Exception) { emptyList() }
            }
            found = r
            searching = false
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = inner.calculateTopPadding() + 28.dp,
                bottom = inner.calculateBottomPadding() + 40.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val w = Modifier.widthIn(max = 640.dp).fillMaxWidth()

            item { Header(version, w) }

            // 내 노트북
            if (saved.isNotEmpty()) {
                item { SectionTitle("내 노트북", w) }
                items(saved, key = { it.host }) { pc ->
                    SavedPcCard(pc, isLatest = pc == saved.first(), modifier = w, onOpen = { onOpen(pc.host, pc.name) }, onForget = { onForget(pc) })
                }
            }

            // 새로 연결
            item { SectionTitle(if (saved.isEmpty()) "연결하기" else "새로 연결", w) }
            item {
                Row(w, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConnectTile(
                        icon = Icons.Rounded.Usb,
                        title = "USB로 연결",
                        desc = "케이블 또는 무선 디버깅 · 가장 빠름",
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(PadActivity.USB_HOST, null) },
                    )
                    ConnectTile(
                        icon = Icons.Rounded.Wifi,
                        title = "Wi‑Fi에서 찾기",
                        desc = "같은 Wi‑Fi의 노트북 자동 검색",
                        modifier = Modifier.weight(1f),
                        onClick = { search() },
                    )
                }
            }

            if (searching) {
                item {
                    SoftCard(w) {
                        Text("같은 Wi‑Fi에서 노트북을 찾는 중…", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    }
                }
            }

            found?.let { list ->
                if (list.isEmpty()) {
                    item {
                        SoftCard(w) {
                            Text("노트북을 찾지 못했어요", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "• 노트북에서 PenBridge가 실행 중인지\n• 태블릿과 노트북이 같은 Wi‑Fi인지\n• 노트북 PenBridge 화면의 '방화벽 허용하기'를 눌렀는지 확인해 주세요.\n그래도 안 되면 아래 '주소 직접 입력'에 노트북 화면의 주소를 넣어 주세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { search() }) { Text("다시 찾기") }
                        }
                    }
                } else {
                    items(list, key = { "found-" + it.address }) { f ->
                        FoundCard(f, w) { onOpen(f.address, f.name) }
                    }
                }
            }

            // 주소 직접 입력
            item {
                SoftCard(w, onClick = { manualOpen = !manualOpen }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("주소 직접 입력", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (manualOpen) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (manualOpen) ManualEntry(prefs, onOpen)
                }
            }

            // 설정
            item { SectionTitle("설정", w) }
            item {
                SoftCard(w, padding = 4.dp) {
                    SettingSwitch("앱을 열면 마지막 노트북에 자동 연결", "매번 연결할 필요 없이 바로 그릴 수 있어요", prefs.autoConnect) { prefs.autoConnect = it }
                    Divider()
                    SettingSwitch("자동 업데이트", "연결하면 노트북에서 새 버전을 받아 설치해요", prefs.autoUpdate) { prefs.autoUpdate = it }
                    Divider()
                    SettingSwitch("노트북 화면 보기 (액정 타블렛 모드)", "툴바의 화면 버튼으로도 켜고 끌 수 있어요", prefs.mirror) { prefs.mirror = it }
                    Divider()
                    SettingSwitch("단축키 툴바", "실행 취소 · 지우개 · 브러시 크기 · 손 도구 · 확대 등", prefs.toolbarEnabled) { prefs.toolbarEnabled = it }
                    Divider()
                    SettingSwitch("툴바를 오른쪽에", "왼손으로 그린다면 켜세요", prefs.toolbarRight) { prefs.toolbarRight = it }
                    Divider()
                    SettingSwitch("손가락 제스처", "두 손가락 탭 = 실행 취소 · 세 손가락 탭 = 다시 실행 · 끌기 = 이동 · 벌리기 = 확대", prefs.gestures) { prefs.gestures = it }
                    Divider()
                    SettingSwitch("펜 위치 표시", "입력판에 펜 끝 위치를 보여 줘요", prefs.showCursor) { prefs.showCursor = it }
                    Divider()
                    SettingSwitch("손가락으로도 그리기", "전용 펜이 없을 때만 켜세요 (필압 일정, 제스처는 꺼짐)", prefs.fingerInput) { prefs.fingerInput = it }
                }
            }

            // 도움말
            item {
                SoftCard(w, onClick = { helpOpen = !helpOpen }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("USB로 처음 연결할 때", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (helpOpen) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (helpOpen) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "1. 설정 → 태블릿 정보 → 소프트웨어 정보에서 '빌드번호'를 7번 눌러요.\n" +
                                "2. 설정 → 개발자 옵션에서 'USB 디버깅'을 켜요.\n" +
                                "3. 케이블을 꽂고 태블릿에 뜨는 'USB 디버깅 허용'을 눌러요.\n" +
                                "4. 노트북 PenBridge 화면에 이 태블릿이 보이면 'USB로 연결'을 눌러요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(version: String, modifier: Modifier) {
    Row(modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF9474FF), Color(0xFF4B37DF)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_brand_glyph), null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("PenBridge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("태블릿을 노트북의 펜 태블릿으로 · v$version",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 10.dp, start = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SoftCard(
    modifier: Modifier,
    padding: androidx.compose.ui.unit.Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(18.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors) {
            Column(Modifier.padding(padding)) { content() }
        }
    } else {
        Card(modifier = modifier, shape = shape, colors = colors) {
            Column(Modifier.padding(padding)) { content() }
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector, highlighted: Boolean = false) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun SavedPcCard(pc: SavedPc, isLatest: Boolean, modifier: Modifier, onOpen: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SoftCard(modifier, padding = 14.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (pc.isUsb) Icons.Rounded.Usb else Icons.Rounded.Laptop, highlighted = isLatest)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (pc.isUsb) "USB 연결" else pc.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isLatest) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "최근",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                val where = if (pc.isUsb) "${pc.name} · 케이블/무선 디버깅" else pc.host
                Text(
                    "$where · ${relative(pc.lastUsed)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("연결") }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, "더 보기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("목록에서 삭제") }, onClick = { menu = false; onForget() })
                }
            }
        }
    }
}

@Composable
private fun ConnectTile(icon: ImageVector, title: String, desc: String, modifier: Modifier, onClick: () -> Unit) {
    SoftCard(modifier, onClick = onClick) {
        IconTile(icon)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FoundCard(f: Discovery.Found, modifier: Modifier, onOpen: () -> Unit) {
    SoftCard(modifier, padding = 14.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Laptop)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onOpen) { Text("연결") }
        }
    }
}

@Composable
private fun ManualEntry(prefs: Prefs, onOpen: (String, String?) -> Unit) {
    var address by remember {
        mutableStateOf(prefs.savedPcs.firstOrNull { !it.isUsb }?.host ?: "")
    }
    val go = { address.trim().takeIf { it.isNotEmpty() }?.let { onOpen(it, null) } }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("예: 192.168.0.12") },
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
        )
        Spacer(Modifier.width(10.dp))
        Button(onClick = { go() }) { Text("연결") }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "노트북 PenBridge 화면의 'Wi‑Fi 연결'에 보이는 주소예요.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingSwitch(title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

private fun relative(time: Long): String {
    if (time <= 0) return "기록 없음"
    val diff = System.currentTimeMillis() - time
    return when {
        diff < DateUtils.MINUTE_IN_MILLIS -> "방금"
        diff < DateUtils.HOUR_IN_MILLIS -> "${diff / DateUtils.MINUTE_IN_MILLIS}분 전"
        diff < DateUtils.DAY_IN_MILLIS -> "${diff / DateUtils.HOUR_IN_MILLIS}시간 전"
        else -> "${diff / DateUtils.DAY_IN_MILLIS}일 전"
    }
}

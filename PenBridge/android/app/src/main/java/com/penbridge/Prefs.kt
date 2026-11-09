package com.penbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 연결했던 노트북 */
data class SavedPc(val name: String, val host: String, val lastUsed: Long) {
    val isUsb: Boolean get() = host == PadActivity.USB_HOST
}

/** 간단한 설정 저장소 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("penbridge", Context.MODE_PRIVATE)

    var lastHost: String?
        get() = sp.getString("last_host", null)
        set(v) = sp.edit().putString("last_host", v).apply()

    var lastName: String?
        get() = sp.getString("last_name", null)
        set(v) = sp.edit().putString("last_name", v).apply()

    /** 앱을 열면 마지막 노트북에 바로 연결 */
    var autoConnect: Boolean
        get() = sp.getBoolean("auto_connect", true)
        set(v) = sp.edit().putBoolean("auto_connect", v).apply()

    /** S펜 같은 전용 펜이 없을 때 손가락/일반 펜도 입력으로 사용 */
    var fingerInput: Boolean
        get() = sp.getBoolean("finger_input", false)
        set(v) = sp.edit().putBoolean("finger_input", v).apply()

    /** 펜 위치 표시 (호버/접촉 점) */
    var showCursor: Boolean
        get() = sp.getBoolean("show_cursor", true)
        set(v) = sp.edit().putBoolean("show_cursor", v).apply()

    /** 노트북에 새 버전이 있으면 연결할 때 자동으로 받아 설치 (기본 켜짐) */
    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    /** 사용자가 설치 권한을 거절한 버전 (같은 버전은 다시 묻지 않음) */
    var skippedVersion: Int
        get() = sp.getInt("skipped_version", 0)
        set(v) = sp.edit().putInt("skipped_version", v).apply()

    /** 툴바 위치: true = 오른쪽 (왼손잡이), false = 왼쪽 */
    var toolbarRight: Boolean
        get() = sp.getBoolean("toolbar_right", false)
        set(v) = sp.edit().putBoolean("toolbar_right", v).apply()

    /** 툴바 접힘 상태 */
    var toolbarCollapsed: Boolean
        get() = sp.getBoolean("toolbar_collapsed", false)
        set(v) = sp.edit().putBoolean("toolbar_collapsed", v).apply()

    /** 툴바 표시 */
    var toolbarEnabled: Boolean
        get() = sp.getBoolean("toolbar_enabled", true)
        set(v) = sp.edit().putBoolean("toolbar_enabled", v).apply()

    /** 손가락 제스처 (두 손가락 탭 = 실행 취소 등) */
    var gestures: Boolean
        get() = sp.getBoolean("gestures", true)
        set(v) = sp.edit().putBoolean("gestures", v).apply()

    /** 액정 타블렛 모드 (노트북 화면 보기) — 마지막 상태 기억 */
    var mirror: Boolean
        get() = sp.getBoolean("mirror", false)
        set(v) = sp.edit().putBoolean("mirror", v).apply()

    fun pinFor(host: String): Int = sp.getInt("pin_$host", -1)
    fun setPin(host: String, pin: Int) = sp.edit().putInt("pin_$host", pin).apply()

    // ───────────── 저장된 노트북 목록 ─────────────

    val savedPcs: List<SavedPc>
        get() {
            val raw = sp.getString("saved_pcs", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    SavedPc(o.getString("name"), o.getString("host"), o.optLong("lastUsed"))
                }.sortedByDescending { it.lastUsed }
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun store(list: List<SavedPc>) {
        val arr = JSONArray()
        list.sortedByDescending { it.lastUsed }.take(10).forEach {
            arr.put(JSONObject().put("name", it.name).put("host", it.host).put("lastUsed", it.lastUsed))
        }
        sp.edit().putString("saved_pcs", arr.toString()).apply()
    }

    /** 연결에 성공하면 기억. 같은 PC(이름)가 IP만 바뀌었으면 기존 항목을 갱신 */
    fun rememberPc(name: String, host: String) {
        val isUsb = host == PadActivity.USB_HOST
        val rest = savedPcs.filterNot {
            it.host == host || (!isUsb && !it.isUsb && it.name.equals(name, ignoreCase = true))
        }
        val oldPin = savedPcs.firstOrNull { !it.isUsb && it.name.equals(name, true) && it.host != host }
            ?.let { pinFor(it.host) } ?: -1
        if (oldPin >= 0 && pinFor(host) < 0) setPin(host, oldPin)
        store(rest + SavedPc(name, host, System.currentTimeMillis()))
        lastHost = host
        lastName = name
    }

    fun forgetPc(host: String) {
        store(savedPcs.filterNot { it.host == host })
        if (lastHost == host) {
            val next = savedPcs.firstOrNull()
            lastHost = next?.host
            lastName = next?.name
        }
    }
}

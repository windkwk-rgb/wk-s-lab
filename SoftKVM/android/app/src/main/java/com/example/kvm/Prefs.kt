package com.example.kvm

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kvm", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v.trim()).apply()
    var port: Int
        get() = sp.getInt("port", 24800)
        set(v) = sp.edit().putInt("port", v).apply()
    var name: String
        get() = sp.getString("name", "android") ?: "android"
        set(v) = sp.edit().putString("name", v.trim()).apply()
    var key: String
        get() = sp.getString("key", "change-me") ?: "change-me"
        set(v) = sp.edit().putString("key", v).apply()

    var iphoneEnabled: Boolean
        get() = sp.getBoolean("iphone_enabled", false)
        set(v) = sp.edit().putBoolean("iphone_enabled", v).apply()
    var iphoneName: String
        get() = sp.getString("iphone_name", "iphone") ?: "iphone"
        set(v) = sp.edit().putString("iphone_name", v.trim()).apply()
    var iphoneAddr: String
        get() = sp.getString("iphone_addr", "") ?: ""
        set(v) = sp.edit().putString("iphone_addr", v).apply()
    /** 이 태블릿 배터리를 화면에 표시 · 위치(tl 좌상단 · ml 좌중단 · bl 좌하단 · tr 우상단 · mr 우중단 · br 우하단) */
    var batteryOverlay: Boolean
        get() = sp.getBoolean("battery_overlay", true)
        set(v) = sp.edit().putBoolean("battery_overlay", v).apply()
    var batteryPos: String
        get() = sp.getString("battery_pos", "tr") ?: "tr"
        set(v) = sp.edit().putString("battery_pos", v).apply()
    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(v) = sp.edit().putBoolean("keep_awake", v).apply()
    var iphoneLandscape: Boolean
        get() = sp.getBoolean("iphone_landscape", false)
        set(v) = sp.edit().putBoolean("iphone_landscape", v).apply()
    /** BLE로 키보드·마우스를 구독한 아이폰 주소 (펜 등 다른 기기와 구분) */
    var iphoneBleAddr: String
        get() = sp.getString("iphone_ble_addr", "") ?: ""
        set(v) = sp.edit().putString("iphone_ble_addr", v).apply()

    /** 연결 프로필: 이름 → {host, port, name, key, saved} (JSON) */
    var profiles: String
        get() = sp.getString("profiles", "{}") ?: "{}"
        set(v) = sp.edit().putString("profiles", v).apply()
    var profileCurrent: String
        get() = sp.getString("profile_current", "") ?: ""
        set(v) = sp.edit().putString("profile_current", v).apply()
}

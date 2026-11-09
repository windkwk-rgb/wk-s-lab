package com.example.kvm

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/** 화면이 꺼져 있을 때 잠깐 띄워서 화면을 켜는 투명한 화면 (곧바로 닫힘) */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Handler(Looper.getMainLooper()).postDelayed({ finish(); overridePendingTransition(0, 0) }, 1200)
    }
}

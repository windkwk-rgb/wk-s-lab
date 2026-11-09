package com.penbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 태블릿 앱 자동 업데이트.
 * PC가 "새 버전 있음(UPDATE_OFFER)"을 보내면, 별도 연결로 APK를 받아(같은 Wi‑Fi 또는 USB) 설치한다.
 * 설치는 Android PackageInstaller를 사용 → Android 12 이상에서는 두 번째 업데이트부터 확인 창 없이 설치.
 */
object AppUpdater {

    fun versionCode(ctx: Context): Int {
        val pi = if (Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
               else @Suppress("DEPRECATION") pi.versionCode
    }

    fun versionString(code: Int) = "${code / 10000}.${code / 100 % 100}.${code % 100}"

    /** PC에서 APK를 받는다. 백그라운드 스레드에서 호출. 받은 versionCode 반환. */
    fun download(host: String, pin: Int, dest: File, onProgress: (Int) -> Unit): Int {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, Protocol.PORT), 3000)
            s.soTimeout = 20000
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            out.write(Protocol.updateRequest(pin))
            out.flush()

            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            val header = ByteArray(Protocol.PACKET_SIZE)
            input.readFully(header)
            if (header[0] == Protocol.REJECT) throw IOException("노트북이 업데이트 요청을 거절했어요 (PIN 확인)")
            if (header[0] != Protocol.UPDATE_DATA) throw IOException("알 수 없는 응답")
            val vc = Protocol.readInt(header, 4)
            val size = Protocol.readLong(header, 8)
            if (size <= 0 || size > 300L * 1024 * 1024) throw IOException("파일 크기가 이상해요")

            dest.outputStream().use { fo ->
                val buf = ByteArray(64 * 1024)
                var left = size
                var lastPct = -1
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("받는 중에 연결이 끊겼어요")
                    fo.write(buf, 0, n)
                    left -= n
                    val pct = ((size - left) * 100 / size).toInt()
                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                }
            }
            return vc
        }
    }

    /** 받은 APK 설치 요청. 결과는 InstallReceiver로 온다. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags,
            )
            session.commit(pending.intentSender)
        }
    }
}

/** 설치 결과 수신. 사용자 확인이 필요하면 확인 창을 띄운다. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(it) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // 앱이 교체되면서 이 프로세스는 곧 종료됨
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Toast.makeText(ctx, "업데이트를 취소했어요", Toast.LENGTH_SHORT).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val hint = if (msg.contains("INCOMPATIBLE") || msg.contains("signatures"))
                    " — 서명이 달라요. 앱을 지우고 새로 설치해 주세요." else ""
                Toast.makeText(ctx, "업데이트 설치 실패: $msg$hint", Toast.LENGTH_LONG).show()
            }
        }
    }
}

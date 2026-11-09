package com.example.kvm

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** 윈도우 서버와의 TCP 연결. 끊기면 자동 재연결. 수신 메시지는 메인 스레드로 전달. */
class NetClient(
    private val host: String,
    private val port: Int,
    private val name: String,
    private val key: String,
    private val sizeProvider: () -> Pair<Int, Int>,
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val platform: String = "android",
    private val appVersion: String = "",
    private val batteryProvider: (() -> Pair<Int, Boolean>?)? = null,   // 배터리 잔량(%)·충전 중
) {
    @Volatile private var running = true
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var latestMove: JSONObject? = null
    @Volatile private var movePosted = false

    fun start() {
        thread(name = "kvm-net", isDaemon = true) { loop() }
    }

    private fun loop() {
        var backoff = 500L
        while (running) {
            try {
                main.post { onStatus("연결 중: $host:$port") }
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), 3000)
                s.soTimeout = 7000      // 메인 PC 신호(2초마다)가 7초 없으면 끊긴 것으로 보고 바로 다시 연결
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                val (w, h) = sizeProvider()
                send(JSONObject().put("t", "hello").put("name", name).put("key", key).put("w", w).put("h", h)
                    .put("platform", platform).put("version", appVersion))
                main.post { onStatus("연결됨: $host") }
                backoff = 500L
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    val obj = try { JSONObject(line) } catch (e: Exception) { continue }
                    val t = obj.optString("t")
                    if (t == "ping") {
                        // 지연 시간 측정용: 화면 처리를 거치지 않고 바로 응답
                        val pong = JSONObject().put("t", "pong").put("ts", obj.opt("ts"))
                        batteryProvider?.invoke()?.let { (lv, chg) -> pong.put("bat", lv).put("chg", chg) }
                        send(pong)
                        continue
                    }
                    if (t == "move") {
                        // 밀린 이동은 최신 좌표 하나만 화면에 반영 → 커서가 늦게 따라오지 않음
                        latestMove = obj
                        if (!movePosted) {
                            movePosted = true
                            main.post {
                                movePosted = false
                                val lm = latestMove
                                latestMove = null
                                lm?.let { onMessage(it) }
                            }
                        }
                    } else {
                        latestMove?.let { lm -> latestMove = null; main.post { onMessage(lm) } }
                        main.post { onMessage(obj) }
                    }
                }
                main.post { onStatus("서버 연결 끊김") }
            } catch (e: Exception) {
                main.post { onStatus("연결 실패: ${e.message}") }
            } finally {
                writer = null
                try { socket?.close() } catch (_: Exception) {}
                main.post { onMessage(JSONObject().put("t", "leave")) }
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(3_000L)    // 끊기면 길어야 3초 안에 다시 시도
        }
    }

    fun send(obj: JSONObject) {
        if (!running) return
        sender.execute {
            try {
                writer?.apply { write(obj.toString()); write("\n"); flush() }
            } catch (_: Exception) {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        sender.shutdown()
    }
}

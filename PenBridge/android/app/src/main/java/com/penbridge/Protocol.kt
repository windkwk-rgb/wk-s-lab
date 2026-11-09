package com.penbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 태블릿 ↔ PC 통신 규격. 모든 패킷은 24바이트 고정, 리틀엔디언. (PROTOCOL.md 참고)
 */
object Protocol {
    const val PORT = 47100
    const val DISCOVERY_PORT = 47101
    const val PACKET_SIZE = 24
    const val VERSION: Byte = 1

    const val HELLO: Byte = 1
    const val PEN: Byte = 2
    const val PING: Byte = 3
    const val PONG: Byte = 4
    const val UPDATE_REQUEST: Byte = 5
    const val DISCOVER: Byte = 6
    const val ACTION: Byte = 7          // [1]=동작 번호, [2]=1 누름 / 0 뗌
    const val GESTURE: Byte = 8         // [1]=1 이동(f0=dx, f1=dy dp) / 2 확대·축소(f0=배율)
    const val STREAM: Byte = 9          // 새 연결의 첫 패킷: 화면 보내기 요청 [4]=PIN, [8]=최대 폭
    const val FRAME: Byte = 16          // PC → 태블릿: [4]=JPEG 길이, [8]=폭, [12]=높이
    const val TOOL_STATE: Byte = 17     // PC → 태블릿: [1]=지우개 켜짐
    const val FRAME_ACK: Byte = 20
    const val DISCOVER_REPLY: Byte = 15
    const val CONFIG: Byte = 10
    const val REJECT: Byte = 11
    const val ACCEPT: Byte = 12
    const val UPDATE_OFFER: Byte = 13
    const val UPDATE_DATA: Byte = 14

    const val HELLO_AUTO_UPDATE = 1

    const val STATE_HOVER = 0
    const val STATE_CONTACT = 1
    const val STATE_OUT = 2

    const val BTN_BARREL = 1
    const val BTN_ERASER = 2
    const val BTN_SECONDARY = 4

    const val DISCOVER_QUERY = "PENBRIDGE?"
    const val DISCOVER_REPLY_TEXT = "PENBRIDGE!"

    private fun buffer(): ByteBuffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    fun hello(pin: Int, viewW: Int, viewH: Int, versionCode: Int, flags: Int): ByteArray = buffer().apply {
        put(0, HELLO); put(1, VERSION)
        putInt(4, pin); putInt(8, viewW); putInt(12, viewH)
        putInt(16, versionCode); put(20, flags.toByte())
    }.array()

    // 동작 번호 (노트북의 Proto.ActionIds와 같은 순서)
    const val A_UNDO = 1
    const val A_REDO = 2
    const val A_ERASER = 3
    const val A_SIZE_DOWN = 4
    const val A_SIZE_UP = 5
    const val A_HAND = 6
    const val A_ZOOM_IN = 7
    const val A_ZOOM_OUT = 8
    const val A_FIT = 9
    const val A_SAVE = 10

    fun action(id: Int, down: Boolean): ByteArray = buffer().apply {
        put(0, ACTION); put(1, id.toByte()); put(2, (if (down) 1 else 0).toByte())
    }.array()

    fun pan(dxDp: Float, dyDp: Float): ByteArray = buffer().apply {
        put(0, GESTURE); put(1, 1); putFloat(4, dxDp); putFloat(8, dyDp)
    }.array()

    fun zoom(ratio: Float): ByteArray = buffer().apply {
        put(0, GESTURE); put(1, 2); putFloat(4, ratio)
    }.array()

    fun stream(pin: Int, maxWidth: Int): ByteArray = buffer().apply {
        put(0, STREAM); put(1, VERSION); putInt(4, pin); putInt(8, maxWidth)
    }.array()

    fun frameAck(): ByteArray = buffer().apply { put(0, FRAME_ACK); put(1, VERSION) }.array()

    fun discover(): ByteArray = buffer().apply { put(0, DISCOVER); put(1, VERSION) }.array()

    /** ACCEPT / DISCOVER_REPLY 안의 PC 이름 ([4]=길이, [8..23]=UTF‑8). 없으면 null */
    fun readName(packet: ByteArray): String? {
        val n = readInt(packet, 4)
        if (n <= 0 || n > 16) return null
        return String(packet, 8, n, Charsets.UTF_8).trim().ifEmpty { null }
    }

    fun updateRequest(pin: Int): ByteArray = buffer().apply {
        put(0, UPDATE_REQUEST); put(1, VERSION); putInt(4, pin)
    }.array()

    fun readInt(packet: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)

    fun readLong(packet: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getLong(offset)

    fun ping(): ByteArray = buffer().apply { put(0, PING); put(1, VERSION) }.array()

    fun pen(state: Int, buttons: Int, x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float): ByteArray =
        buffer().apply {
            put(0, PEN); put(1, state.toByte()); put(2, buttons.toByte())
            putFloat(4, x); putFloat(8, y); putFloat(12, pressure)
            putFloat(16, tiltX); putFloat(20, tiltY)
        }.array()

    fun readFloat(packet: ByteArray, offset: Int): Float =
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getFloat(offset)
}

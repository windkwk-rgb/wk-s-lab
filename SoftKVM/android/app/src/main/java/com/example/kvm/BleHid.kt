package com.example.kvm

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.UUID

private typealias GC = BluetoothGattCharacteristic

/**
 * 저전력 블루투스(BLE) 방식의 키보드·마우스 (HID over GATT).
 * 일반 블루투스 키보드 기능이 꺼져 있는 기기에서도 아이폰에 연결할 수 있음.
 */
@TargetApi(28)
@SuppressLint("MissingPermission")
class BleHid(
    private val ctx: Context,
    private val reportMap: ByteArray,
    private val knownHost: () -> String,          // 예전에 연결했던 아이폰 주소
    private val log: (String) -> Unit,
    private val onConnected: (BluetoothDevice?) -> Unit,
) {
    companion object {
        private fun u16(x: Int) = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", x))
        private val HID = u16(0x1812); private val DIS = u16(0x180A); private val BAS = u16(0x180F)
        private val HID_INFO = u16(0x2A4A); private val REPORT_MAP = u16(0x2A4B); private val CONTROL = u16(0x2A4C)
        private val REPORT = u16(0x2A4D); private val PROTOCOL = u16(0x2A4E)
        private val MANUFACTURER = u16(0x2A29); private val PNP = u16(0x2A50); private val BATTERY = u16(0x2A19)
        private val CCCD = u16(0x2902); private val REPORT_REF = u16(0x2908)
    }

    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private val reports = HashMap<Int, BluetoothGattCharacteristic>()   // 보고서 번호 → 특성
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    @Volatile private var device: BluetoothDevice? = null
    private var advertising = false

    // 보내기 대기열: 한 번에 하나씩 보내고, 전송 완료 신호가 오면 다음 것을 보냄
    private val queue = ArrayDeque<Pair<Int, ByteArray>>()
    private var busy = false
    private var mButtons = 0; private var mDx = 0; private var mDy = 0; private var mWheel = 0
    private var mouseDirty = false

    val connected get() = device != null

    fun start(): Boolean {
        val s = manager?.openGattServer(ctx, callback) ?: run { log("BLE 서버를 열 수 없어요"); return false }
        server = s
        pendingServices.add(hidService())
        pendingServices.add(simpleService(DIS, mapOf(
            MANUFACTURER to "KVM".toByteArray(),
            PNP to byteArrayOf(0x02, 0x5E, 0x04, 0x01, 0x00, 0x01, 0x00))))   // USB 공급자 ID 형식
        pendingServices.add(simpleService(BAS, mapOf(BATTERY to byteArrayOf(100))))
        addNextService()
        return true
    }

    fun stop() {
        stopAdvertising()
        try { device?.let { server?.cancelConnection(it) } } catch (_: Exception) {}   // 아이폰만 끊음 (펜 등은 그대로)
        try { server?.close() } catch (_: Exception) {}
        server = null; device = null
    }

    // ------------------------------------------------------------ GATT 서비스 구성
    private fun hidService(): BluetoothGattService {
        val svc = BluetoothGattService(HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(BluetoothGattCharacteristic(HID_INFO, GC.PROPERTY_READ, GC.PERMISSION_READ_ENCRYPTED).apply {
            value = byteArrayOf(0x11, 0x01, 0x00, 0x03)
        })
        svc.addCharacteristic(BluetoothGattCharacteristic(REPORT_MAP, GC.PROPERTY_READ, GC.PERMISSION_READ_ENCRYPTED).apply {
            value = reportMap
        })
        svc.addCharacteristic(BluetoothGattCharacteristic(CONTROL, GC.PROPERTY_WRITE_NO_RESPONSE, GC.PERMISSION_WRITE_ENCRYPTED))
        svc.addCharacteristic(BluetoothGattCharacteristic(PROTOCOL, GC.PROPERTY_READ or GC.PROPERTY_WRITE_NO_RESPONSE,
            GC.PERMISSION_READ_ENCRYPTED or GC.PERMISSION_WRITE_ENCRYPTED).apply { value = byteArrayOf(0x01) })
        for ((id, size) in listOf(1 to 8, 2 to 4, 3 to 2)) {
            val c = BluetoothGattCharacteristic(REPORT, GC.PROPERTY_READ or GC.PROPERTY_NOTIFY, GC.PERMISSION_READ_ENCRYPTED)
            c.value = ByteArray(size)
            c.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED).apply { value = byteArrayOf(0, 0) })
            c.addDescriptor(BluetoothGattDescriptor(REPORT_REF, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED).apply {
                value = byteArrayOf(id.toByte(), 0x01)        // 보고서 번호, 입력 보고서
            })
            svc.addCharacteristic(c)
            reports[id] = c
        }
        return svc
    }

    private fun simpleService(uuid: UUID, chars: Map<UUID, ByteArray>): BluetoothGattService {
        val svc = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        for ((cu, v) in chars) {
            svc.addCharacteristic(BluetoothGattCharacteristic(cu, GC.PROPERTY_READ, GC.PERMISSION_READ).apply { value = v })
        }
        return svc
    }

    private fun addNextService() {
        val svc = pendingServices.poll()
        if (svc == null) { log("BLE 키보드·마우스 준비 완료"); startAdvertising(); return }
        if (server?.addService(svc) != true) log("BLE 서비스 추가 실패 ${svc.uuid}")
    }

    // ------------------------------------------------------------ 광고 (아이폰 블루투스 목록에 보이게)
    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true; log("아이폰에서 찾을 수 있게 알리는 중") }
        override fun onStartFailure(errorCode: Int) { advertising = false; log("BLE 알림 시작 실패 ($errorCode)") }
    }

    private fun startAdvertising() {
        val adv = manager?.adapter?.bluetoothLeAdvertiser ?: run { log("이 기기는 BLE 알림을 지원하지 않아요"); return }
        if (advertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(HID)).setIncludeTxPowerLevel(false).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        try { adv.startAdvertising(settings, data, scan, advCallback) } catch (e: Exception) { log("BLE 알림 오류: $e") }
    }

    private fun stopAdvertising() {
        try { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        advertising = false
    }

    // ------------------------------------------------------------ 보내기
    /** 보고서 번호 1=키보드(8바이트) 2=마우스(4바이트) 3=미디어(2바이트) */
    @Synchronized
    fun send(id: Int, data: ByteArray) {
        if (device == null) return
        if (id == 2) {
            // 마우스 이동은 쌓아뒀다가 한 번에 보냄 (BLE는 초당 보낼 수 있는 양이 적음)
            if (data[0].toInt() != mButtons && mouseDirty) flushMouse()
            mButtons = data[0].toInt(); mDx += data[1]; mDy += data[2]; mWheel += data[3]
            mouseDirty = true
        } else {
            queue.add(id to data)
        }
        pump()
    }

    @Synchronized
    private fun flushMouse() {
        val dx = mDx.coerceIn(-127, 127); val dy = mDy.coerceIn(-127, 127); val w = mWheel.coerceIn(-127, 127)
        queue.add(2 to byteArrayOf(mButtons.toByte(), dx.toByte(), dy.toByte(), w.toByte()))
        mDx -= dx; mDy -= dy; mWheel -= w
        mouseDirty = mDx != 0 || mDy != 0 || mWheel != 0
    }

    @Synchronized
    private fun pump() {
        if (busy) return
        if (queue.isEmpty() && mouseDirty) flushMouse()
        val (id, data) = queue.poll() ?: return
        val c = reports[id] ?: return
        val dev = device ?: return
        busy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(dev, c, false, data) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            c.value = data
            server?.notifyCharacteristicChanged(dev, c, false) == true
        }
        if (!ok) busy = false
    }

    @Synchronized
    private fun sent() { busy = false; pump() }

    private fun useHost(dev: BluetoothDevice) {
        device = dev
        if (dev.bondState == BluetoothDevice.BOND_NONE) dev.createBond()
        stopAdvertising()
        onConnected(dev)
    }

    // ------------------------------------------------------------ GATT 서버 콜백
    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) { addNextService() }

        override fun onConnectionStateChange(dev: BluetoothDevice, status: Int, newState: Int) {
            log("BLE 연결 상태 ${dev.address} (${dev.name ?: "?"}) → $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // 펜·이어폰처럼 태블릿에 붙는 다른 기기도 여기로 들어오므로,
                // 우리 키보드·마우스를 '구독'한 기기(또는 예전에 연결했던 아이폰)만 대상으로 삼음
                if (dev.address == knownHost()) useHost(dev)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device == dev) {
                device = null
                synchronized(this@BleHid) { queue.clear(); busy = false; mouseDirty = false; mDx = 0; mDy = 0; mWheel = 0 }
                onConnected(null)
                startAdvertising()
            }
        }

        override fun onCharacteristicReadRequest(dev: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value ?: ByteArray(0)
            val part = if (offset < v.size) v.copyOfRange(offset, v.size) else ByteArray(0)
            server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
        }

        override fun onDescriptorReadRequest(dev: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            @Suppress("DEPRECATION") val v = d.value ?: ByteArray(0)
            val part = if (offset < v.size) v.copyOfRange(offset, v.size) else ByteArray(0)
            server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
        }

        override fun onCharacteristicWriteRequest(dev: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorWriteRequest(dev: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (value != null) d.value = value
            if (d.uuid == CCCD) {
                log("${dev.name ?: dev.address} 이(가) 입력 받기를 시작함")
                if (device != dev) useHost(dev)
            }
            if (responseNeeded) server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(dev: BluetoothDevice, status: Int) { sent() }
    }
}

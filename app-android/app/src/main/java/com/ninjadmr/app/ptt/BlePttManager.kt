package com.ninjadmr.app.ptt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Wireless PTT button over Bluetooth Low Energy.
 *
 * Cheap DMR PTT pucks (K5, E-387, BR1 and clones) all expose the same shape:
 * a serial-style service on 0xFFE0 with one notify characteristic on 0xFFE1
 * that reports 0x01 while the pad is held and 0x00 on release.  We scan for a
 * device matching a known name (or advertising that service), subscribe, and
 * forward the pad state to [PttController], which the radio service already
 * listens on.
 *
 * Everything here is best-effort: the radio must keep working if no button is
 * present, so no failure path is allowed to reach the session.
 */
@SuppressLint("MissingPermission")
class BlePttManager(private val context: Context) {

    interface Listener {
        fun onScanStarted() {}
        fun onDeviceFound(name: String, address: String) {}
        fun onConnected(name: String, address: String) {}
        fun onDisconnected() {}
        fun onPtt(active: Boolean) {}
        fun onLog(line: String) {}
    }

    private val adapter by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listener: Listener? = null
    private var gatt: BluetoothGatt? = null
    private var connectedAddress: String? = null
    private var lastPtt = false
    private var scanning = false

    val connectedAddressOrNull: String?
        get() = connectedAddress

    /** True when a button pad is linked and reporting. */
    val isConnected: Boolean
        get() = connectedAddress != null

    // -- permissions -------------------------------------------------------

    fun missingPermissions(): List<String> {
        val wanted = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        wanted += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    // -- scan --------------------------------------------------------------

    fun startScan(target: Listener, timeoutMs: Long = 8_000L) {
        listener = target
        val a = adapter
        if (a == null) {
            target.onLog("ble: no bluetooth adapter")
            return
        }
        if (!a.isEnabled) {
            target.onLog("ble: bluetooth is off")
            return
        }
        if (missingPermissions().isNotEmpty()) {
            target.onLog("ble: bluetooth permission missing")
            return
        }
        stopScan()
        target.onScanStarted()
        target.onLog("ble: scanning for a PTT button…")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            a.bluetoothLeScanner?.startScan(null, settings, scanCallback)
            scanning = true
            mainHandler.removeCallbacks(scanTimeout)
            mainHandler.postDelayed(scanTimeout, timeoutMs)
        } catch (exc: SecurityException) {
            target.onLog("ble: scan denied (${exc.message})")
        }
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // Scanner already gone; nothing to release.
        }
    }

    private val scanTimeout = Runnable {
        stopScan()
        listener?.onLog("ble: scan finished")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onLog("ble: scan failed (code $errorCode)")
        }
    }

    private fun handleResult(result: ScanResult) {
        if (scanning && matches(result)) {
            val name = safeName(result.device)
            val address = result.device.address ?: return
            listener?.onDeviceFound(name, address)
            listener?.onLog("ble: found $name ($address)")
            stopScan()
            connect(result.device, listener ?: return)
        }
    }

    private fun matches(result: ScanResult): Boolean {
        val name = safeName(result.device).lowercase()
        if (KNOWN_BUTTONS.any { name.contains(it) }) return true
        val uuids = result.scanRecord?.serviceUuids ?: return false
        return uuids.any { it.uuid == SERVICE_UUID }
    }

    // -- connect -----------------------------------------------------------

    fun connect(device: BluetoothDevice, target: Listener) {
        listener = target
        stopScan()
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        target.onLog("ble: connecting to ${safeName(device)}…")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        val was = connectedAddress
        connectedAddress = null
        lastPtt = false
        if (was != null) {
            PttController.setPtt(false)
            listener?.onDisconnected()
            listener?.onLog("ble: disconnected")
        }
    }

    fun close() {
        disconnect()
        stopScan()
        listener = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedAddress = g.device.address
                    listener?.onLog("ble: linked, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val was = connectedAddress
                    connectedAddress = null
                    lastPtt = false
                    PttController.setPtt(false)
                    try {
                        g.close()
                    } catch (_: Exception) {
                    }
                    if (was != null) {
                        listener?.onDisconnected()
                        listener?.onLog("ble: link lost")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onLog("ble: service discovery failed ($status)")
                return
            }
            val notify = findNotifyCharacteristic(g)
            if (notify == null) {
                listener?.onLog("ble: no notify characteristic found")
                return
            }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            val name = safeName(g.device)
            listener?.onConnected(name, g.device.address)
            listener?.onLog("ble: PTT button ready ($name)")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { onPad(g, it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onPad(g, value)
        }
    }

    private fun onPad(g: BluetoothGatt, value: ByteArray) {
        val first = value.firstOrNull()?.toInt() ?: return
        val active = first != 0
        if (active == lastPtt) return
        lastPtt = active
        PttController.setPtt(active)
        listener?.onPtt(active)
    }

    private fun findNotifyCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic? {
        val preferred = g.getService(SERVICE_UUID)?.getCharacteristic(NOTIFY_UUID)
        if (preferred != null &&
            preferred.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        ) {
            return preferred
        }
        for (service in g.services) {
            for (char in service.characteristics) {
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    return char
                }
            }
        }
        return null
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name ?: "unknown"
    } catch (_: SecurityException) {
        "unknown"
    }

    companion object {
        /** Serial-style service used by virtually every cheap PTT puck. */
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Name fragments commonly broadcast by DMR PTT buttons. */
        private val KNOWN_BUTTONS = listOf(
            "k5", "k4", "e-387", "e387", "br1", "br-1", "ptt", "sommet",
            "md-380", "md380", "zastone", "villico", "talkie", "hm-10",
        )
    }
}

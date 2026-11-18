package com.ninjaradio.svx

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android USB-to-UART Serial Controller for Analog Radio (HT) Gateway.
 *
 * Provides:
 * 1. Baud Rate configuration (1200 - 115200 bps).
 * 2. Hardware PTT output (RTS, DTR, or Serial Command) to key the HT transmitter.
 * 3. Hardware COS (Carrier Operated Squelch) input (CTS, DSR, or Serial Command)
 *    to trigger SvxLink transmission when HT receives RF audio.
 */
class UsbSerialController(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    enum class PttMode {
        RTS_ACTIVE_HIGH,
        RTS_ACTIVE_LOW,
        DTR_ACTIVE_HIGH,
        DTR_ACTIVE_LOW,
        SERIAL_COMMAND
    }

    enum class CosMode {
        DISABLED_OR_VOX,
        CTS_ACTIVE_HIGH,
        CTS_ACTIVE_LOW,
        DSR_ACTIVE_HIGH,
        DSR_ACTIVE_LOW,
        SERIAL_COMMAND
    }

    interface Listener {
        fun onDeviceAttached(chipName: String)
        fun onDeviceDetached()
        fun onConnected(chipName: String, baudRate: Int)
        fun onDisconnected()
        fun onCosStateChanged(active: Boolean)
        fun onError(msg: String)
    }

    var listener: Listener? = null
    var pttMode: PttMode = PttMode.RTS_ACTIVE_HIGH
    var cosMode: CosMode = CosMode.DISABLED_OR_VOX
    var activeBaudRate: Int = 9600
        private set

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var activeDevice: UsbDevice? = null

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    private var currentDtr = false
    private var currentRts = false
    private var lastCosState = false

    companion object {
        const val ACTION_USB_PERMISSION = "com.ninjaradio.svx.USB_PERMISSION"

        // Common USB UART Chip Vendors
        const val VID_WCH = 0x1A86        // CH340 / CH341
        const val VID_SILABS = 0x10C4     // CP2102 / CP2104
        const val VID_FTDI = 0x0403       // FT232R
        const val VID_PROLIFIC = 0x067B   // PL2303
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    onLog("Izin USB diizinkan: ${device.deviceName}")
                    openDeviceInternal(device, activeBaudRate)
                } else {
                    onLog("Izin USB ditolak oleh pengguna")
                    listener?.onError("Izin akses USB ditolak")
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
    }

    fun release() {
        disconnect()
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {}
    }

    fun getConnectedDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    fun identifyChip(device: UsbDevice): String = when (device.vendorId) {
        VID_WCH -> "CH340/CH341 (HT Cable)"
        VID_SILABS -> "CP2102 (Silicon Labs)"
        VID_FTDI -> "FTDI FT232"
        VID_PROLIFIC -> "Prolific PL2303"
        else -> {
            if (device.deviceClass == UsbConstants.USB_CLASS_COMM ||
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM }
            ) {
                "CDC-ACM (Arduino/ESP32/Pico)"
            } else {
                "USB-UART (VID:0x${Integer.toHexString(device.vendorId)})"
            }
        }
    }

    fun connect(device: UsbDevice, baudRate: Int = 9600) {
        activeDevice = device
        activeBaudRate = baudRate

        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, intent)
            onLog("Meminta izin akses USB untuk ${identifyChip(device)}...")
            return
        }

        openDeviceInternal(device, baudRate)
    }

    fun disconnect() {
        isRunning.set(false)
        try { workerThread?.interrupt() } catch (_: Exception) {}
        workerThread = null

        // Unkey PTT before close
        try {
            setLines(dtr = false, rts = false)
        } catch (_: Exception) {}

        connection?.let { conn ->
            usbInterface?.let { iface ->
                try { conn.releaseInterface(iface) } catch (_: Exception) {}
            }
            try { conn.close() } catch (_: Exception) {}
        }
        connection = null
        usbInterface = null
        inEndpoint = null
        outEndpoint = null
        activeDevice = null
        lastCosState = false
        listener?.onDisconnected()
    }

    val isConnected: Boolean
        get() = isRunning.get() && connection != null

    private fun openDeviceInternal(device: UsbDevice, baudRate: Int) {
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            listener?.onError("Gagal membuka USB connection (null)")
            return
        }
        connection = conn

        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null
        var targetIface: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && foundIn == null) foundIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT && foundOut == null) foundOut = ep
                }
            }
            if (foundIn != null && foundOut != null) {
                targetIface = iface
                break
            }
        }

        if (targetIface == null) {
            conn.close()
            listener?.onError("Endpoint Bulk IN/OUT tidak ditemukan pada USB device ini")
            return
        }

        conn.claimInterface(targetIface, true)
        usbInterface = targetIface
        inEndpoint = foundIn
        outEndpoint = foundOut

        // Setup baud rate & initial line states
        configureBaudRate(device, conn, baudRate)
        setLines(dtr = false, rts = false)

        isRunning.set(true)
        startWorkerLoop()

        val chip = identifyChip(device)
        onLog("USB Serial Terhubung: $chip @ $baudRate baud")
        listener?.onConnected(chip, baudRate)
    }

    private fun configureBaudRate(device: UsbDevice, conn: UsbDeviceConnection, baudRate: Int) {
        when (device.vendorId) {
            VID_WCH -> {
                // CH340 initialization sequence
                conn.controlTransfer(0x40, 0xa1, 0xc29c, 0xb800, null, 0, 1000)
                val factor = when (baudRate) {
                    1200 -> 0xd980
                    2400 -> 0x6481
                    4800 -> 0xb281
                    9600 -> 0xb282
                    19200 -> 0xd982
                    38400 -> 0x6483
                    57600 -> 0x9883
                    115200 -> 0xcc83
                    else -> 0xb282
                }
                conn.controlTransfer(0x40, 0x9a, 0x1312, factor, null, 0, 1000)
                conn.controlTransfer(0x40, 0xa4, 0x00ff, 0x0000, null, 0, 1000)
            }
            VID_SILABS -> {
                // CP2102 baud rate configuration
                conn.controlTransfer(0x41, 0x00, 0x0001, 0, null, 0, 1000) // Enable UART
                val data = byteArrayOf(
                    (baudRate and 0xFF).toByte(),
                    ((baudRate shr 8) and 0xFF).toByte(),
                    ((baudRate shr 16) and 0xFF).toByte(),
                    ((baudRate shr 24) and 0xFF).toByte()
                )
                conn.controlTransfer(0x41, 0x1E, 0, 0, data, data.size, 1000) // SET_BAUDRATE
            }
            VID_FTDI -> {
                // FTDI baud divisor
                val divisor = 3000000 / baudRate
                conn.controlTransfer(0x40, 0x03, divisor and 0xFFFF, 0, null, 0, 1000)
            }
            else -> {
                // Standard CDC-ACM line coding (Baud, 1 stop bit, no parity, 8 data bits)
                val lineCoding = byteArrayOf(
                    (baudRate and 0xFF).toByte(),
                    ((baudRate shr 8) and 0xFF).toByte(),
                    ((baudRate shr 16) and 0xFF).toByte(),
                    ((baudRate shr 24) and 0xFF).toByte(),
                    0x00, // 1 stop bit
                    0x00, // no parity
                    0x08  // 8 data bits
                )
                conn.controlTransfer(0x21, 0x20, 0, 0, lineCoding, lineCoding.size, 1000)
            }
        }
    }

    /**
     * Set hardware RTS and DTR lines on the USB UART chip.
     */
    private fun setLines(dtr: Boolean, rts: Boolean) {
        val conn = connection ?: return
        val device = activeDevice ?: return
        currentDtr = dtr
        currentRts = rts

        try {
            when (device.vendorId) {
                VID_WCH -> {
                    // CH340 vendor request 0xA4: Bit 5 is RTS, Bit 6 is DTR (active low internally)
                    var value = 0
                    if (!dtr) value = value or 0x20
                    if (!rts) value = value or 0x40
                    conn.controlTransfer(0x40, 0xa4, value, 0, null, 0, 500)
                }
                VID_SILABS -> {
                    // CP2102 vendor request 0x07: 0x0101 = DTR HIGH, 0x0202 = RTS HIGH
                    var value = 0
                    value = value or (if (dtr) 0x0101 else 0x0100)
                    value = value or (if (rts) 0x0202 else 0x0200)
                    conn.controlTransfer(0x41, 0x07, value, 0, null, 0, 500)
                }
                VID_FTDI -> {
                    // FTDI SIO_SET_MODEM_CTRL_REQUEST
                    val dtrVal = if (dtr) 0x0101 else 0x0100
                    val rtsVal = if (rts) 0x0202 else 0x0200
                    conn.controlTransfer(0x40, 0x01, dtrVal, 0, null, 0, 500)
                    conn.controlTransfer(0x40, 0x01, rtsVal, 0, null, 0, 500)
                }
                else -> {
                    // CDC-ACM SET_CONTROL_LINE_STATE
                    val value = (if (rts) 0x02 else 0x00) or (if (dtr) 0x01 else 0x00)
                    conn.controlTransfer(0x21, 0x22, value, 0, null, 0, 500)
                }
            }
        } catch (e: Exception) {
            onLog("Error setting serial lines: ${e.message}")
        }
    }

    /**
     * Trigger HT Transmit (PTT) when remote SvxLink station speaks.
     */
    fun setHtPtt(keyTx: Boolean) {
        if (!isConnected) return
        when (pttMode) {
            PttMode.RTS_ACTIVE_HIGH -> setLines(dtr = currentDtr, rts = keyTx)
            PttMode.RTS_ACTIVE_LOW -> setLines(dtr = currentDtr, rts = !keyTx)
            PttMode.DTR_ACTIVE_HIGH -> setLines(dtr = keyTx, rts = currentRts)
            PttMode.DTR_ACTIVE_LOW -> setLines(dtr = !keyTx, rts = currentRts)
            PttMode.SERIAL_COMMAND -> {
                val cmd = if (keyTx) "PTT:1\n" else "PTT:0\n"
                sendSerialData(cmd.toByteArray())
            }
        }
    }

    /**
     * Send raw data or ASCII command over USB UART.
     */
    fun sendSerialData(data: ByteArray) {
        val conn = connection ?: return
        val ep = outEndpoint ?: return
        try {
            conn.bulkTransfer(ep, data, data.size, 1000)
        } catch (e: Exception) {
            onLog("Serial TX error: ${e.message}")
        }
    }

    /**
     * Read hardware modem status register (CTS, DSR, CD) from the USB UART.
     */
    private fun readModemStatus(): Int {
        val conn = connection ?: return -1
        val device = activeDevice ?: return -1
        val buf = ByteArray(2)

        return try {
            when (device.vendorId) {
                VID_WCH -> {
                    // CH340 vendor request 0x95
                    val len = conn.controlTransfer(0xc0, 0x95, 0x0706, 0, buf, 2, 500)
                    if (len >= 1) buf[0].toInt() and 0xFF else -1
                }
                VID_SILABS -> {
                    // CP2102 vendor request 0x08
                    val len = conn.controlTransfer(0xc1, 0x08, 0, 0, buf, 1, 500)
                    if (len >= 1) buf[0].toInt() and 0xFF else -1
                }
                else -> -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Background worker: polls hardware COS lines and reads serial incoming data.
     */
    private fun startWorkerLoop() {
        workerThread = Thread({
            val readBuf = ByteArray(256)
            var strAccum = StringBuilder()

            while (isRunning.get()) {
                val conn = connection ?: break
                val epIn = inEndpoint

                // 1. Check Hardware COS Pins (CTS / DSR) if applicable
                if (cosMode == CosMode.CTS_ACTIVE_HIGH || cosMode == CosMode.CTS_ACTIVE_LOW ||
                    cosMode == CosMode.DSR_ACTIVE_HIGH || cosMode == CosMode.DSR_ACTIVE_LOW) {
                    val status = readModemStatus()
                    if (status != -1) {
                        val device = activeDevice
                        val isCts = if (device?.vendorId == VID_WCH) (status and 0x01) == 0 else (status and 0x10) != 0
                        val isDsr = if (device?.vendorId == VID_WCH) (status and 0x02) == 0 else (status and 0x20) != 0

                        val cosActive = when (cosMode) {
                            CosMode.CTS_ACTIVE_HIGH -> isCts
                            CosMode.CTS_ACTIVE_LOW -> !isCts
                            CosMode.DSR_ACTIVE_HIGH -> isDsr
                            CosMode.DSR_ACTIVE_LOW -> !isDsr
                            else -> false
                        }

                        if (cosActive != lastCosState) {
                            lastCosState = cosActive
                            listener?.onCosStateChanged(cosActive)
                        }
                    }
                }

                // 2. Read incoming serial stream (for Serial Command mode or log)
                if (epIn != null) {
                    val r = conn.bulkTransfer(epIn, readBuf, readBuf.size, 50)
                    if (r > 0) {
                        val chunk = String(readBuf, 0, r)
                        strAccum.append(chunk)

                        var newlineIdx = strAccum.indexOf('\n')
                        while (newlineIdx != -1) {
                            val line = strAccum.substring(0, newlineIdx).trim()
                            strAccum.delete(0, newlineIdx + 1)

                            if (cosMode == CosMode.SERIAL_COMMAND) {
                                if (line.contains("COS:1", ignoreCase = true) || line.contains("SQL:1", ignoreCase = true)) {
                                    if (!lastCosState) { lastCosState = true; listener?.onCosStateChanged(true) }
                                } else if (line.contains("COS:0", ignoreCase = true) || line.contains("SQL:0", ignoreCase = true)) {
                                    if (lastCosState) { lastCosState = false; listener?.onCosStateChanged(false) }
                                }
                            }
                            newlineIdx = strAccum.indexOf('\n')
                        }

                        if (strAccum.length > 512) strAccum.setLength(0)
                    }
                }

                try { Thread.sleep(30) } catch (_: InterruptedException) { break }
            }
        }, "svx-usb-worker").apply { start() }
    }
}

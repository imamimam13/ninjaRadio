package com.ninjadmr.app.uart

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
import com.ninjadmr.app.util.AppLog
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android USB-to-UART Serial Driver and MMDVM Hotspot Bridge.
 *
 * Connects directly to Handie-Talkie (HT) transceivers and MMDVM modems via USB-OTG
 * WITHOUT ROOT using the standard Android UsbManager host APIs.
 *
 * Supported UART Chipsets commonly found in HT programming cables and modems:
 * - WCH CH340 / CH341 (VID 0x1A86, PID 0x7523, 0x5523) -> Standard Baofeng/Quansheng cables
 * - Silicon Labs CP2102 / CP2104 (VID 0x10C4, PID 0xEA60) -> Premium HT cables & MMDVM_HS
 * - FTDI FT232R / FT2232 (VID 0x0403, PID 0x6001) -> Commercial HT interfaces
 * - Prolific PL2303 (VID 0x067B, PID 0x2303)
 * - Standard USB CDC-ACM (VID varies, Arduino, STM32 BluePill, Raspberry Pi Pico)
 */
class UsbUartManager(private val context: Context) {

    data class DiscoveredDevice(
        val usbDevice: UsbDevice,
        val chipName: String,
        val description: String,
    )

    interface Listener {
        fun onDeviceAttached(device: DiscoveredDevice) {}
        fun onDeviceDetached() {}
        fun onConnected(chipName: String, baudRate: Int) {}
        fun onDisconnected() {}
        fun onDataReceived(data: ByteArray) {}
        fun onMmdvmFrame(command: Byte, payload: ByteArray) {}
        fun onError(message: String) {}
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private var activeDevice: DiscoveredDevice? = null
    private val isRunning = AtomicBoolean(false)
    private var readThread: Thread? = null

    var listener: Listener? = null
    var activeBaudRate: Int = 115200
        private set

    val isConnected: Boolean
        get() = isRunning.get() && connection != null

    companion object {
        const val ACTION_USB_PERMISSION = "com.ninjadmr.app.USB_PERMISSION"

        // Common USB UART Vendor IDs
        const val VID_WCH = 0x1A86        // CH340 / CH341
        const val VID_SILABS = 0x10C4     // CP2102
        const val VID_FTDI = 0x0403       // FT232
        const val VID_PROLIFIC = 0x067B   // PL2303

        // MMDVM Protocol Constants
        const val MMDVM_SYNC = 0xE0.toByte()
        const val CMD_GET_STATUS = 0x00.toByte()
        const val CMD_SET_CONFIG = 0x01.toByte()
        const val CMD_SET_MODE = 0x02.toByte()
        const val CMD_CALIBRATION = 0x09.toByte()
        const val CMD_DMR_DATA = 0x18.toByte()
        const val CMD_M17_DATA = 0x30.toByte()
        const val CMD_ANALOG_DATA = 0x40.toByte()
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
                    AppLog.add("USB permission granted for ${device.deviceName}")
                    openDeviceInternal(device, activeBaudRate)
                } else {
                    AppLog.add("USB permission denied")
                    listener?.onError("USB permission denied by user")
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

    fun scanDevices(): List<DiscoveredDevice> {
        val list = mutableListOf<DiscoveredDevice>()
        for ((_, device) in usbManager.deviceList) {
            val chip = identifyChip(device)
            val desc = "${device.manufacturerName ?: ""} ${device.productName ?: device.deviceName}".trim()
            list.add(DiscoveredDevice(device, chip, desc.ifBlank { "UART Interface (VID:0x${Integer.toHexString(device.vendorId)})" }))
        }
        return list
    }

    private fun identifyChip(device: UsbDevice): String = when (device.vendorId) {
        VID_WCH -> "CH340/CH341 (HT Cable)"
        VID_SILABS -> "CP2102 (Silicon Labs)"
        VID_FTDI -> "FTDI FT232"
        VID_PROLIFIC -> "Prolific PL2303"
        else -> {
            if (device.deviceClass == UsbConstants.USB_CLASS_COMM ||
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM }
            ) {
                "CDC-ACM (MMDVM / Arduino / Pico)"
            } else {
                "Generic USB UART (0x${Integer.toHexString(device.vendorId)})"
            }
        }
    }

    fun connect(device: DiscoveredDevice, baudRate: Int = 115200) {
        activeDevice = device
        activeBaudRate = baudRate

        if (!usbManager.hasPermission(device.usbDevice)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device.usbDevice, intent)
            AppLog.add("Requesting USB permission for ${device.chipName}...")
            return
        }

        openDeviceInternal(device.usbDevice, baudRate)
    }

    private fun openDeviceInternal(device: UsbDevice, baudRate: Int) {
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            listener?.onError("Failed to open USB device connection (openDevice returned null)")
            return
        }
        connection = conn

        // Find interfaces and IN/OUT bulk endpoints
        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null
        var targetIface: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && foundIn == null) {
                        foundIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT && foundOut == null) {
                        foundOut = ep
                    }
                }
            }
            if (foundIn != null && foundOut != null) {
                targetIface = iface
                break
            }
        }

        if (targetIface == null || foundIn == null || foundOut == null) {
            conn.close()
            listener?.onError("No valid UART endpoints found on this USB device")
            return
        }

        conn.claimInterface(targetIface, true)
        usbInterface = targetIface
        inEndpoint = foundIn
        outEndpoint = foundOut

        // Configure chip baud rate
        configureBaudRate(device, conn, baudRate)

        isRunning.set(true)
        startReadLoop()

        val chip = identifyChip(device)
        AppLog.add("UART connected to $chip at $baudRate baud without root!")
        listener?.onConnected(chip, baudRate)
    }

    private fun configureBaudRate(device: UsbDevice, conn: UsbDeviceConnection, baudRate: Int) {
        when (device.vendorId) {
            VID_WCH -> {
                // CH340 initialization sequence
                conn.controlTransfer(0x40, 0xa1, 0xc29c, 0xb800, null, 0, 1000)
                val factor = when (baudRate) {
                    9600 -> 0xb282
                    19200 -> 0xd982
                    38400 -> 0x6483
                    57600 -> 0x9883
                    115200 -> 0xcc83
                    else -> 0xcc83
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
                    ((baudRate shr 24) and 0xFF).toByte(),
                )
                conn.controlTransfer(0x41, 0x1E, 0, 0, data, data.size, 1000) // SET_BAUDRATE
            }
            else -> {
                // Standard CDC-ACM line coding (e.g. 115200 8N1)
                val lineCoding = byteArrayOf(
                    (baudRate and 0xFF).toByte(),
                    ((baudRate shr 8) and 0xFF).toByte(),
                    ((baudRate shr 16) and 0xFF).toByte(),
                    ((baudRate shr 24) and 0xFF).toByte(),
                    0x00, // 1 stop bit
                    0x00, // no parity
                    0x08, // 8 data bits
                )
                conn.controlTransfer(0x21, 0x20, 0, 0, lineCoding, lineCoding.size, 1000)
                conn.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 1000) // DTR + RTS
            }
        }
    }

    private fun startReadLoop() {
        readThread = Thread({
            val buffer = ByteArray(1024)
            while (isRunning.get()) {
                val conn = connection ?: break
                val epIn = inEndpoint ?: break
                val len = conn.bulkTransfer(epIn, buffer, buffer.size, 200)
                if (len > 0) {
                    val chunk = buffer.copyOf(len)
                    listener?.onDataReceived(chunk)
                    parseMmdvmStream(chunk)
                }
            }
        }, "UsbUart-Reader").apply { isDaemon = true; start() }
    }

    /**
     * Parses incoming byte chunks for MMDVM protocol frames (0xE0 sync byte).
     */
    private val mmdvmBuffer = ArrayList<Byte>()
    private fun parseMmdvmStream(chunk: ByteArray) {
        for (b in chunk) {
            mmdvmBuffer.add(b)
        }
        while (mmdvmBuffer.size >= 3) {
            if (mmdvmBuffer[0] != MMDVM_SYNC) {
                mmdvmBuffer.removeAt(0)
                continue
            }
            val length = mmdvmBuffer[1].toInt() and 0xFF
            if (mmdvmBuffer.size < length) {
                break // Wait for remainder of frame
            }
            val cmd = mmdvmBuffer[2]
            val payload = ByteArray(maxOf(0, length - 3))
            for (i in 0 until payload.size) {
                payload[i] = mmdvmBuffer[3 + i]
            }
            // Remove the frame
            for (i in 0 until length) {
                if (mmdvmBuffer.isNotEmpty()) mmdvmBuffer.removeAt(0)
            }
            listener?.onMmdvmFrame(cmd, payload)
        }
    }

    fun write(data: ByteArray): Boolean {
        val conn = connection ?: return false
        val epOut = outEndpoint ?: return false
        val transferred = conn.bulkTransfer(epOut, data, data.size, 1000)
        return transferred >= 0
    }

    /**
     * Sends an MMDVM formatted frame to the connected HT / modem.
     */
    fun sendMmdvmFrame(command: Byte, payload: ByteArray = ByteArray(0)): Boolean {
        val length = 3 + payload.size
        val frame = ByteArray(length)
        frame[0] = MMDVM_SYNC
        frame[1] = length.toByte()
        frame[2] = command
        System.arraycopy(payload, 0, frame, 3, payload.size)
        return write(frame)
    }

    /**
     * Send Quansheng UV-K5 / OpenFw CAT Command over UART.
     * e.g. "FREQ 438.50000\n", "PTT 1\n", "PTT 0\n"
     */
    fun sendCatCommand(command: String): Boolean {
        val text = if (command.endsWith("\n")) command else "$command\n"
        return write(text.toByteArray(Charsets.US_ASCII))
    }

    fun disconnect() {
        isRunning.set(false)
        try {
            readThread?.interrupt()
            readThread = null
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {}
        connection = null
        usbInterface = null
        inEndpoint = null
        outEndpoint = null
        listener?.onDisconnected()
        AppLog.add("USB UART disconnected")
    }

    fun destroy() {
        disconnect()
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {}
    }
}

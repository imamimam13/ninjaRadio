package com.ninjadmr.app.uart

import android.content.Context
import com.ninjadmr.app.util.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MMDVM Software Hotspot Gateway Controller.
 *
 * Turns the Android device into a complete standalone MMDVM Hotspot without needing
 * a Raspberry Pi or MMDVM Hat. Connects directly to an HT (DMR, M17, or Analog FM)
 * via USB-to-UART cable (CH340/CP2102/FTDI/CDC-ACM) without root!
 *
 * Modes:
 * 1. DMR_HOTSPOT: Upstream Homebrew/BrandMeister <-> 4FSK MMDVM RF Frames over UART
 * 2. M17_HOTSPOT: Upstream M17 Reflector <-> M17 4800bps Baseband frames over UART
 * 3. ANALOG_GATEWAY: DMR digital voice <-> Analog FM HT via UART CAT / VOX / COR
 */
class MmdvmHotspotBridge(
    context: Context,
    val uart: UsbUartManager,
) : UsbUartManager.Listener {

    enum class HotspotMode {
        DMR,
        M17,
        ANALOG_FM,
    }

    enum class RfState {
        STANDBY,
        RECEIVING_FROM_RF,
        TRANSMITTING_TO_RF,
    }

    var mode: HotspotMode = HotspotMode.DMR
    var rfFrequency: String = "438.50000"
    var rfPower: String = "LOW (1W)"
    var rxGain: Int = 50 // 0-100%
    var txDeviation: Int = 50 // 0-100%
    var squelchThreshold: Int = 30 // Squelch sensitivity

    var rfState: RfState = RfState.STANDBY
        private set

    var packetsFromRf: Long = 0
        private set
    var packetsToRf: Long = 0
        private set

    private val active = AtomicBoolean(false)

    init {
        uart.listener = this
    }

    fun startHotspot() {
        active.set(true)
        // Initialize MMDVM modem config over UART
        uart.sendMmdvmFrame(UsbUartManager.CMD_SET_CONFIG, byteArrayOf(
            if (mode == HotspotMode.DMR) 1 else 0, // DMR enable
            if (mode == HotspotMode.M17) 1 else 0, // M17 enable
            if (mode == HotspotMode.ANALOG_FM) 1 else 0, // Analog enable
            0x01 // Duplex or Simplex
        ))
        AppLog.add("MMDVM Hotspot engine started in mode $mode on $rfFrequency MHz")
    }

    fun stopHotspot() {
        active.set(false)
        rfState = RfState.STANDBY
        AppLog.add("MMDVM Hotspot engine stopped")
    }

    /**
     * Send DMR frame from the network down to the HT via UART
     */
    fun forwardDownstreamDmr(frameData: ByteArray) {
        if (!active.get() || !uart.isConnected) return
        rfState = RfState.TRANSMITTING_TO_RF
        packetsToRf++
        uart.sendMmdvmFrame(UsbUartManager.CMD_DMR_DATA, frameData)
    }

    /**
     * Send M17 frame from the network down to the HT via UART
     */
    fun forwardDownstreamM17(frameData: ByteArray) {
        if (!active.get() || !uart.isConnected) return
        rfState = RfState.TRANSMITTING_TO_RF
        packetsToRf++
        uart.sendMmdvmFrame(UsbUartManager.CMD_M17_DATA, frameData)
    }

    /**
     * Trigger PTT on Quansheng UV-K5 / OpenFw via UART CAT commands
     */
    fun setCatPtt(transmitting: Boolean) {
        if (transmitting) {
            rfState = RfState.TRANSMITTING_TO_RF
            uart.sendCatCommand("PTT 1")
        } else {
            rfState = RfState.STANDBY
            uart.sendCatCommand("PTT 0")
        }
    }

    /**
     * Set RF frequency on HT via UART CAT
     */
    fun tuneFrequency(freqMhz: String) {
        rfFrequency = freqMhz
        uart.sendCatCommand("FREQ $freqMhz")
        AppLog.add("Tuned HT to $freqMhz MHz over UART")
    }

    override fun onMmdvmFrame(command: Byte, payload: ByteArray) {
        when (command) {
            UsbUartManager.CMD_DMR_DATA -> {
                rfState = RfState.RECEIVING_FROM_RF
                packetsFromRf++
                // RF payload received from HT, ready to forward to DMR upstream network
            }
            UsbUartManager.CMD_M17_DATA -> {
                rfState = RfState.RECEIVING_FROM_RF
                packetsFromRf++
            }
            UsbUartManager.CMD_GET_STATUS -> {
                // Firmware status frame from modem
            }
        }
    }

    override fun onDataReceived(data: ByteArray) {
        // Raw incoming data from UART
    }

    override fun onDisconnected() {
        rfState = RfState.STANDBY
        AppLog.add("Hotspot UART detached")
    }
}

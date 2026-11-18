package com.ninjaradio.svx

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Opus 16 kHz mono audio I/O with Hardware Audio Routing & VOX Hysteresis.
 *
 * Capabilities:
 * - Routing selector: Default (Phone / 3.5mm TRRS) vs USB Soundcard (CM108 / OTG DAC).
 * - VOX (Voice Operated eXchange) Schmitt trigger with 450ms hangover timer (Rule 7.3).
 * - Full-duplex RX AudioTrack & TX AudioRecord.
 */
class AudioEngine(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    enum class AudioRoute {
        DEFAULT_OR_TRRS,
        USB_SOUNDCARD,
        SPEAKERPHONE
    }

    companion object {
        const val RATE = 16000
        const val FRAME = 320          // 20 ms @ 16 kHz
        const val MAX_FRAME = 1920     // 120 ms max Opus frame
        const val PREBUFFER = 4        // ~80 ms jitter cushion
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val decoder = OpusDecoder(RATE, 1)
    private val rxQueue = LinkedBlockingQueue<ShortArray>(200)

    @Volatile private var playing = false
    private var playThread: Thread? = null

    @Volatile private var txActive = false
    private var txThread: Thread? = null

    var currentRoute: AudioRoute = AudioRoute.DEFAULT_OR_TRRS
        set(value) {
            field = value
            applyAudioRoute()
        }

    // VOX Hysteresis Configuration
    var voxEnabled: Boolean = false
    var voxAttackThreshold: Int = 30    // 0 - 100%
    var voxReleaseThreshold: Int = 15   // 0 - 100%
    var voxHangoverMs: Long = 450L      // Hangover silence delay
    var onVoxStateChanged: ((Boolean) -> Unit)? = null

    @Volatile private var isVoxTriggered = false
    private var lastVoiceDetectedTime: Long = 0L

    // ----------------------------------------------------------------- Audio Device Routing

    fun applyAudioRoute() {
        try {
            when (currentRoute) {
                AudioRoute.SPEAKERPHONE -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = true
                }
                AudioRoute.DEFAULT_OR_TRRS -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                }
                AudioRoute.USB_SOUNDCARD -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                }
            }
        } catch (e: Exception) {
            onLog("Gagal menerapkan audio routing: ${e.message}")
        }
    }

    private fun findUsbAudioDevice(isInput: Boolean): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val flags = if (isInput) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
            val devices = audioManager.getDevices(flags)
            for (dev in devices) {
                if (dev.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                ) {
                    return dev
                }
            }
        }
        return null
    }

    // ----------------------------------------------------------------- RX

    fun start() {
        if (playing) return
        playing = true
        applyAudioRoute()
        playThread = Thread({ playLoop() }, "svx-play").apply { start() }
    }

    fun stop() {
        playing = false
        stopTx()
        playThread?.interrupt()
        rxQueue.clear()
    }

    fun onOpusPacket(data: ByteArray) {
        try {
            val pcm = ShortArray(MAX_FRAME)
            val n = synchronized(decoder) {
                decoder.decode(data, 0, data.size, pcm, 0, MAX_FRAME, false)
            }
            if (n > 0) {
                if (!rxQueue.offer(pcm.copyOf(n))) {
                    rxQueue.poll()
                    rxQueue.offer(pcm.copyOf(n))
                }
            }
        } catch (e: Exception) {
            onLog("Opus decode error: ${e.message}")
        }
    }

    fun flush() { /* let queued audio drain naturally */ }

    private fun playLoop() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("DEPRECATION")
        val track = AudioTrack(
            AudioManager.STREAM_VOICE_CALL, RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 8), AudioTrack.MODE_STREAM
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentRoute == AudioRoute.USB_SOUNDCARD) {
            findUsbAudioDevice(isInput = false)?.let { usbDev ->
                track.preferredDevice = usbDev
                onLog("AudioTrack output diarahkan ke USB: ${usbDev.productName}")
            }
        }

        track.play()
        var buffering = true
        try {
            while (playing) {
                if (buffering) {
                    if (rxQueue.size >= PREBUFFER) buffering = false
                    else { Thread.sleep(10); continue }
                }
                val frame = rxQueue.poll(200, TimeUnit.MILLISECONDS)
                if (frame == null) { buffering = true; continue }
                track.write(frame, 0, frame.size)
            }
        } catch (_: InterruptedException) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    // ----------------------------------------------------------------- TX

    @SuppressLint("MissingPermission")
    fun startTx(
        onFrame: (ByteArray) -> Unit,
        onLevel: (Int) -> Unit
    ) {
        if (txActive) return
        txActive = true
        txThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 4)
                )
            } catch (e: Exception) {
                onLog("Mic error: ${e.message}")
                txActive = false
                return@Thread
            }

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                onLog("Mic tidak tersedia (cek izin RECORD_AUDIO)")
                rec.release()
                txActive = false
                return@Thread
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentRoute == AudioRoute.USB_SOUNDCARD) {
                findUsbAudioDevice(isInput = true)?.let { usbDev ->
                    rec.preferredDevice = usbDev
                    onLog("AudioRecord input diarahkan ke USB: ${usbDev.productName}")
                }
            }

            val enc = OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
            enc.bitrate = 20000
            enc.complexity = 5
            enc.signalType = OpusSignal.OPUS_SIGNAL_VOICE
            val pcm = ShortArray(FRAME)
            val outBuf = ByteArray(1275)
            rec.startRecording()

            try {
                while (txActive) {
                    var got = 0
                    while (got < FRAME && txActive) {
                        val r = rec.read(pcm, got, FRAME - got)
                        if (r <= 0) break
                        got += r
                    }
                    if (got < FRAME) continue

                    var peak = 0
                    for (s in pcm) {
                        val a = kotlin.math.abs(s.toInt())
                        if (a > peak) peak = a
                    }
                    val levelPct = (peak * 100) / 32768
                    onLevel(levelPct)

                    // Software VOX Hysteresis
                    if (voxEnabled) {
                        val now = System.currentTimeMillis()
                        if (levelPct >= voxAttackThreshold) {
                            lastVoiceDetectedTime = now
                            if (!isVoxTriggered) {
                                isVoxTriggered = true
                                onVoxStateChanged?.invoke(true)
                            }
                        } else if (levelPct < voxReleaseThreshold) {
                            if (isVoxTriggered && (now - lastVoiceDetectedTime >= voxHangoverMs)) {
                                isVoxTriggered = false
                                onVoxStateChanged?.invoke(false)
                            }
                        }
                    }

                    val len = enc.encode(pcm, 0, FRAME, outBuf, 0, outBuf.size)
                    if (len > 0) onFrame(outBuf.copyOf(len))
                }
            } catch (e: Exception) {
                onLog("TX error: ${e.message}")
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                onLevel(0)
            }
        }, "svx-tx").apply { start() }
    }

    fun stopTx() {
        txActive = false
        try { txThread?.join(300) } catch (_: InterruptedException) {}
        txThread = null
        isVoxTriggered = false
    }
}

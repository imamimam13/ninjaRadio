package com.ninjaradio.ultimate.audio

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
import kotlin.math.abs

/**
 * High-performance, low-latency audio engine supporting dynamic sample rates:
 * - 48 kHz: Native Mumble (Studio-grade Opus VoIP)
 * - 16 kHz: Native SvxLink Reflector (Opus Wideband)
 * - 8 kHz: Native DMR & RoIP USRP (8kHz Telecom Standard)
 *
 * Includes real-time S-Meter calculation, VOX hysteresis, pre-buffering jitter cushion,
 * and audio device routing.
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

    var sampleRate: Int = 8000
        private set
    val frameSize: Int get() = (sampleRate * 20) / 1000       // 20ms: 160@8k, 320@16k, 960@48k
    val maxFrameSize: Int get() = frameSize * 6              // up to 120ms Opus frame

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var decoder: OpusDecoder = OpusDecoder(sampleRate, 1)
    private var track: AudioTrack? = null
    private val rxQueue = LinkedBlockingQueue<ShortArray>(40)
    @Volatile private var isRunning = false
    private var playThread: Thread? = null

    @Volatile private var txActive = false
    private var txThread: Thread? = null

    var currentRoute: AudioRoute = AudioRoute.SPEAKERPHONE
        set(value) {
            field = value
            applyAudioRoute()
        }

    // Real-time Audio Level Callbacks (0 - 100%)
    var onRxLevel: ((Int) -> Unit)? = null

    // VOX Hysteresis Configuration
    var voxEnabled: Boolean = false
    var voxAttackThreshold: Int = 30
    var voxReleaseThreshold: Int = 15
    var voxHangoverMs: Long = 450L
    var onVoxStateChanged: ((Boolean) -> Unit)? = null

    @Volatile private var isVoxTriggered = false
    private var lastVoiceDetectedTime: Long = 0L

    // Audio Processing & Gain Configurations
    var micGainDb: Int = 6 // +6 dB boost default
    var rxGainDb: Int = 0  // 0 dB default
    var rogerBeepEnabled: Boolean = true
    var keyBeepEnabled: Boolean = true

    fun configure(newRate: Int) {
        val targetRate = when (newRate) {
            48000 -> 48000
            16000 -> 16000
            else -> 8000
        }
        if (sampleRate == targetRate && isRunning) return
        stop()
        sampleRate = targetRate
        synchronized(this) {
            decoder = OpusDecoder(sampleRate, 1)
        }
        start()
    }

    fun applyAudioRoute() {
        try {
            when (currentRoute) {
                AudioRoute.SPEAKERPHONE -> {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = true
                }
                AudioRoute.DEFAULT_OR_TRRS -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                }
                AudioRoute.USB_SOUNDCARD -> {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = false
                }
            }
        } catch (e: Exception) {
            onLog("Gagal routing audio: ${e.message}")
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
        if (track != null) return
        applyAudioRoute()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = (if (minBuf > 0) minBuf * 2 else frameSize * 8).coerceAtLeast(frameSize * 8)

        try {
            val it = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                AudioTrack(attrs, fmt, bufSize, AudioTrack.MODE_STREAM, 0)
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM
                )
            }
            if (it.state == AudioTrack.STATE_INITIALIZED) {
                it.play()
                track = it
                isRunning = true
                playThread = Thread({
                    while (isRunning) {
                        try {
                            val pcm = rxQueue.poll(100, TimeUnit.MILLISECONDS)
                            if (pcm != null && pcm.isNotEmpty()) {
                                val t = track
                                if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                                    t.write(pcm, 0, pcm.size)
                                }
                            }
                        } catch (_: InterruptedException) {
                            break
                        } catch (e: Exception) {
                            // ignore write errors
                        }
                    }
                }, "ninja-play").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
            }
        } catch (e: Exception) {
            onLog("AudioTrack init failed: ${e.message}")
        }
    }

    fun stop() {
        stopTx()
        isRunning = false
        playThread?.interrupt()
        try { playThread?.join(300) } catch (_: Exception) {}
        playThread = null
        rxQueue.clear()
        try { track?.pause(); track?.flush(); track?.stop(); track?.release() } catch (_: Exception) {}
        track = null
        onRxLevel?.invoke(0)
    }

    fun onOpusPacket(data: ByteArray) {
        try {
            val maxLen = maxFrameSize
            val pcm = ShortArray(maxLen)
            val n = synchronized(this) {
                decoder.decode(data, 0, data.size, pcm, 0, maxLen, false)
            }
            if (n > 0) {
                onPcmAudio(pcm.copyOf(n))
            }
        } catch (e: Exception) {
            onLog("Opus decode err: ${e.message}")
        }
    }

    fun onPcmAudio(pcm: ShortArray) {
        if (pcm.isNotEmpty()) {
            val rxGainFactor = if (rxGainDb != 0) Math.pow(10.0, rxGainDb / 20.0) else 1.0
            val processedPcm = if (rxGainFactor != 1.0) {
                val amplified = ShortArray(pcm.size)
                for (i in pcm.indices) {
                    amplified[i] = (pcm[i] * rxGainFactor).toInt().coerceIn(-32768, 32767).toShort()
                }
                amplified
            } else {
                pcm
            }

            var peak = 0
            for (s in processedPcm) {
                val a = abs(s.toInt())
                if (a > peak) peak = a
            }
            val levelPct = (peak * 100) / 32768
            onRxLevel?.invoke(levelPct)

            // Prevent queue lag: keep cushion under 25 packets
            while (rxQueue.size >= 25) {
                rxQueue.poll()
            }
            rxQueue.offer(processedPcm)
        }
    }

    fun flush() {
        rxQueue.clear()
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (_: Exception) {}
        onRxLevel?.invoke(0)
    }

    // ----------------------------------------------------------------- TX

    @SuppressLint("MissingPermission")
    fun startTx(
        onOpusFrame: (ByteArray) -> Unit,
        onPcm8Frame: ((ShortArray) -> Unit)? = null,
        onLevel: (Int) -> Unit
    ) {
        if (txActive) return
        txActive = true
        val currentRate = sampleRate
        val currentFrame = frameSize

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {}

        txThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(currentRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = Math.max(minBuf * 2, 16000)
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            )
            var rec: AudioRecord? = null
            for (src in sources) {
                try {
                    val candidate = AudioRecord(src, currentRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        rec = candidate
                        break
                    } else {
                        candidate.release()
                    }
                } catch (_: Exception) {}
            }

            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                onLog("Mic tidak siap (cek izin RECORD_AUDIO)")
                rec?.release()
                txActive = false
                try { applyAudioRoute() } catch (_: Exception) {}
                return@Thread
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentRoute == AudioRoute.USB_SOUNDCARD) {
                findUsbAudioDevice(isInput = true)?.let { usbDev ->
                    rec.preferredDevice = usbDev
                    onLog("AudioRecord -> USB: ${usbDev.productName}")
                }
            }

            val enc = OpusEncoder(currentRate, 1, OpusApplication.OPUS_APPLICATION_VOIP)
            enc.bitrate = when (currentRate) {
                48000 -> 48000
                16000 -> 24000
                else -> 16000
            }
            enc.complexity = 8
            enc.signalType = OpusSignal.OPUS_SIGNAL_VOICE
            val pcm = ShortArray(currentFrame)
            val outBuf = ByteArray(1275)

            try {
                rec.startRecording()
            } catch (e: Exception) {
                onLog("Gagal start recording: ${e.message}")
                rec.release()
                txActive = false
                try { applyAudioRoute() } catch (_: Exception) {}
                return@Thread
            }

            try {
                while (txActive) {
                    var got = 0
                    while (got < currentFrame && txActive) {
                        val r = rec.read(pcm, got, currentFrame - got)
                        if (r < 0) {
                            onLog("AudioRecord error: $r")
                            break
                        }
                        if (r == 0) {
                            Thread.sleep(5)
                            continue
                        }
                        got += r
                    }
                    if (got < currentFrame) continue

                    // Apply Mic Gain boost/reduction with soft-clipping limiter
                    val gainFactor = if (micGainDb != 0) Math.pow(10.0, micGainDb / 20.0) else 1.0
                    for (i in 0 until got) {
                        val boosted = (pcm[i] * gainFactor).toInt()
                        pcm[i] = if (boosted > 30000) {
                            (30000 + (boosted - 30000) / 4).coerceAtMost(32767).toShort()
                        } else if (boosted < -30000) {
                            (-30000 + (boosted + 30000) / 4).coerceAtLeast(-32768).toShort()
                        } else {
                            boosted.toShort()
                        }
                    }

                    var peak = 0
                    for (s in pcm) {
                        val a = abs(s.toInt())
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

                    // Feed 8kHz PCM to DMR AMBE encoder or RoIP if requested
                    if (onPcm8Frame != null) {
                        if (currentRate == 8000) {
                            onPcm8Frame(pcm.copyOf())
                        } else if (currentRate == 48000) {
                            val pcm8 = ShortArray(160)
                            for (k in 0 until 160) {
                                pcm8[k] = pcm[k * 6]
                            }
                            onPcm8Frame(pcm8)
                        } else if (currentRate == 16000) {
                            val pcm8 = ShortArray(160)
                            for (k in 0 until 160) {
                                pcm8[k] = pcm[k * 2]
                            }
                            onPcm8Frame(pcm8)
                        }
                    }

                    val len = enc.encode(pcm, 0, currentFrame, outBuf, 0, outBuf.size)
                    if (len > 0) onOpusFrame(outBuf.copyOf(len))
                }
            } catch (e: Exception) {
                onLog("TX error: ${e.message}")
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                onLevel(0)
                try { applyAudioRoute() } catch (_: Exception) {}
            }
        }, "ninja-audio-tx").apply { start() }
    }

    fun stopTx() {
        txActive = false
        try { txThread?.join(300) } catch (_: InterruptedException) {}
        txThread = null
        isVoxTriggered = false
        try { applyAudioRoute() } catch (_: Exception) {}
        playRogerBeep()
    }

    /**
     * Synthesizes authentic dual-tone Motorola Roger Beep (880Hz + 1200Hz).
     */
    fun playRogerBeep() {
        if (!rogerBeepEnabled) return
        Thread({
            try {
                val sRate = sampleRate
                val durationMs = 100
                val numSamples = (sRate * durationMs) / 1000
                val samples = ShortArray(numSamples)
                val half = numSamples / 2
                // Tone 1: 880 Hz for 50ms
                for (i in 0 until half) {
                    val angle = 2.0 * Math.PI * i / (sRate / 880.0)
                    samples[i] = (Math.sin(angle) * 14000).toInt().toShort()
                }
                // Tone 2: 1200 Hz for 50ms
                for (i in half until numSamples) {
                    val angle = 2.0 * Math.PI * (i - half) / (sRate / 1200.0)
                    samples[i] = (Math.sin(angle) * 14000).toInt().toShort()
                }
                onPcmAudio(samples)
            } catch (_: Exception) {}
        }, "roger-beep").start()
    }

    /**
     * Short tactical UI key click beep.
     */
    fun playKeyBeep() {
        if (!keyBeepEnabled) return
        Thread({
            try {
                val sRate = sampleRate
                val numSamples = (sRate * 25) / 1000
                val samples = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i / (sRate / 1500.0)
                    samples[i] = (Math.sin(angle) * 8000).toInt().toShort()
                }
                onPcmAudio(samples)
            } catch (_: Exception) {}
        }, "key-beep").start()
    }
}

package com.ninjaradio.svx

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10

/**
 * High-performance Opus Audio Engine for NinjaSvxLink (16 kHz mono).
 */
class AudioEngine(private val onLog: (String) -> Unit) {

    companion object {
        const val RATE = 16000
        const val FRAME = 320          // 20 ms @ 16 kHz
        const val MAX_FRAME = 1920     // 120 ms max Opus frame
    }

    private val decoder = OpusDecoder(RATE, 1)
    private val rxQueue = LinkedBlockingQueue<ShortArray>(200)
    @Volatile private var playing = false
    private var playThread: Thread? = null

    @Volatile private var txActive = false
    private var txThread: Thread? = null

    @Volatile var micVolume: Float = 1.0f  // 0.0 to 2.0
    @Volatile var rxVolume: Float = 1.0f   // 0.0 to 2.0

    var onRxLevelListener: ((levelPct: Int, db: Double) -> Unit)? = null
    var onTxLevelListener: ((levelPct: Int, db: Double) -> Unit)? = null

    // ----------------------------------------------------------------- Audio Output Routing

    @Suppress("DEPRECATION")
    fun setAudioOutput(context: Context, useSpeaker: Boolean) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = useSpeaker
        } catch (e: Exception) {
            onLog("Audio routing error: ${e.message}")
        }
    }

    // ----------------------------------------------------------------- RX Playback

    fun start() {
        if (playing) return
        playing = true
        playThread = Thread({ playLoop() }, "svx-play").apply { start() }
    }

    fun stop() {
        playing = false
        stopTx()
        playThread?.interrupt()
        rxQueue.clear()
        onRxLevelListener?.invoke(0, -96.0)
    }

    fun onOpusPacket(data: ByteArray) {
        try {
            val pcm = ShortArray(MAX_FRAME)
            val n = synchronized(decoder) {
                decoder.decode(data, 0, data.size, pcm, 0, MAX_FRAME, false)
            }
            if (n > 0) {
                val frame = pcm.copyOf(n)

                // Calculate RX peak audio level
                var peak = 0
                for (s in frame) {
                    val a = abs(s.toInt())
                    if (a > peak) peak = a
                }
                val pct = ((peak * 100) / 32768).coerceIn(0, 100)
                val db = if (peak > 0) 20 * log10(peak / 32768.0) else -96.0
                onRxLevelListener?.invoke(pct, db)

                // Apply RX Volume scale
                if (rxVolume != 1.0f) {
                    for (i in frame.indices) {
                        val scaled = (frame[i] * rxVolume).toInt().coerceIn(-32768, 32767)
                        frame[i] = scaled.toShort()
                    }
                }

                if (!rxQueue.offer(frame)) {
                    rxQueue.poll()
                    rxQueue.offer(frame)
                }
            }
        } catch (e: Exception) {
            onLog("Opus decode error: ${e.message}")
        }
    }

    fun flush() {
        rxQueue.clear()
        synchronized(decoder) {
            try {
                decoder.resetState()
            } catch (_: Exception) {}
        }
        onRxLevelListener?.invoke(0, -96.0)
    }

    private fun playLoop() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("DEPRECATION")
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME * 2 * 8),
            AudioTrack.MODE_STREAM,
        )
        track.play()
        try {
            while (playing) {
                val frame = rxQueue.poll(200, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    track.write(frame, 0, frame.size)
                } else {
                    onRxLevelListener?.invoke(0, -96.0)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
            onRxLevelListener?.invoke(0, -96.0)
        }
    }

    // ----------------------------------------------------------------- TX Recording

    @SuppressLint("MissingPermission")
    fun startTx(onFrame: (ByteArray) -> Unit) {
        if (txActive) return
        txActive = true
        txThread = Thread(
            {
                val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, FRAME * 2 * 4),
                    )
                } catch (e: Exception) {
                    onLog("Mic error: ${e.message}")
                    txActive = false
                    return@Thread
                }

                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    onLog("Mic not initialized")
                    rec.release()
                    txActive = false
                    return@Thread
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
                        while ((got < FRAME) && txActive) {
                            val r = rec.read(pcm, got, FRAME - got)
                            if (r <= 0) break
                            got += r
                        }
                        if (got < FRAME) continue

                        // Apply Mic Volume gain
                        if (micVolume != 1.0f) {
                            for (i in 0 until FRAME) {
                                val scaled = (pcm[i] * micVolume).toInt().coerceIn(-32768, 32767)
                                pcm[i] = scaled.toShort()
                            }
                        }

                        // Level calculation
                        var peak = 0
                        for (s in pcm) {
                            val a = abs(s.toInt())
                            if (a > peak) peak = a
                        }
                        val pct = ((peak * 100) / 32768).coerceIn(0, 100)
                        val db = if (peak > 0) 20 * log10(peak / 32768.0) else -96.0
                        onTxLevelListener?.invoke(pct, db)

                        val len = enc.encode(pcm, 0, FRAME, outBuf, 0, outBuf.size)
                        if (len > 0 && txActive) onFrame(outBuf.copyOf(len))
                    }
                } catch (e: Exception) {
                    onLog("TX error: ${e.message}")
                } finally {
                    try { rec.stop() } catch (_: Exception) {}
                    rec.release()
                    onTxLevelListener?.invoke(0, -96.0)
                }
            },
            "svx-tx",
        ).apply { start() }
    }

    fun stopTx() {
        if (!txActive) return
        txActive = false
        onTxLevelListener?.invoke(0, -96.0)
        // Clean up thread asynchronously without blocking caller UI thread
        val currentThread = txThread
        Thread(
            {
                try { currentThread?.join(200) } catch (_: Exception) {}
                if (txThread == currentThread) txThread = null
            },
            "svx-tx-stop",
        ).start()
    }
}

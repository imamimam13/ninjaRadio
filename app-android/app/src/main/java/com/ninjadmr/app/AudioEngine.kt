package com.ninjadmr.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.ninjadmr.app.dsp.Agc
import com.ninjadmr.app.dsp.Biquad
import com.ninjadmr.app.dsp.Envelope
import com.ninjadmr.app.dsp.JitterBuffer

/**
 * 8 kHz mono PCM capture/playback for the DMR vocoder path.
 *
 * * Capture: frames are accumulated into 60 ms (480 sample) bursts, high-pass
 *   filtered (to strip DC offset and handling rumble before the AMBE vocoder),
 *   smoothly leveled, and handed to [onMicFrame].
 * * Playback: decoded bursts are buffered via [JitterBuffer] (3 bursts / 180 ms cushion)
 *   to eliminate underrun pops, gently leveled, smooth onset envelope applied,
 *   and written to the output track by a dedicated thread.
 */
class AudioEngine(
    private val sampleRate: Int = 8000,
    private val onMicFrame: (ShortArray) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "NinjaDMR-Audio"
        const val FRAME_SAMPLES = 160
        const val BURST_SAMPLES = 480
    }

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    @Volatile private var running = false

    // TX AGC: targets 6000 RMS, bounded gain [0.5, 2.5] with per-sample interpolation
    private val txAgc = Agc(targetRms = 6000.0, maxGain = 2.5, minGain = 0.5)
    // RX AGC: gentle speech leveling (AMBE output is already synthesized and bounded)
    private val rxAgc = Agc(targetRms = 6500.0, maxGain = 1.5, minGain = 0.7)
    // High-pass filter at 160 Hz for microphone capture (eliminates DC offset and sub-audible puff/rumble)
    private val txHp = Biquad().highPass(sampleRate.toDouble(), 160.0)
    // Jitter buffer with 3 frames (180 ms) cushion to absorb internet packet jitter without clicks
    private val jitter = JitterBuffer(BURST_SAMPLES, cushionFrames = 3)
    private val micAccum = ShortArray(BURST_SAMPLES)
    private var micPos = 0
    @Volatile private var lastMicRms = 0
    fun micRms(): Int = if (running && isCapturing()) lastMicRms else 0

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (record != null) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError("AudioRecord.getMinBufferSize failed: $minBuf")
            return
        }
        val bufSize = maxOf(minBuf, FRAME_SAMPLES * 2 * 8)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (_: Exception) {
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            } catch (exc: Exception) {
                onError("AudioRecord init failed: ${exc.message}")
                return
            }
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("AudioRecord not initialized")
            return
        }
        record = rec
        rec.startRecording()
        running = true
        captureThread = Thread({ captureLoop(rec) }, "dmr-capture").apply { start() }
    }

    fun isCapturing(): Boolean = record?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    private fun captureLoop(rec: AudioRecord) {
        val frame = ShortArray(FRAME_SAMPLES)
        while (running) {
            var read = 0
            while (read < FRAME_SAMPLES && running) {
                val n = rec.read(frame, read, FRAME_SAMPLES - read)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "AudioRecord.read error $n")
                    break
                }
                read += n
            }
            if (read != FRAME_SAMPLES) continue
            var offset = 0
            while (offset < frame.size) {
                val n = minOf(BURST_SAMPLES - micPos, frame.size - offset)
                System.arraycopy(frame, offset, micAccum, micPos, n)
                micPos += n
                offset += n
                if (micPos == BURST_SAMPLES) {
                    val burst = micAccum.copyOf()
                    // 1. High-pass filter to clean up DC offset & low rumble before vocoder
                    for (i in burst.indices) {
                        val hp = txHp.process(burst[i].toDouble())
                        burst[i] = hp.coerceIn(-32768.0, 32767.0).toInt().toShort()
                    }
                    // 2. Smooth AGC
                    txAgc.process(burst)
                    var sum = 0L
                    for (s in burst) sum += (s.toLong() * s.toLong())
                    lastMicRms = kotlin.math.sqrt((sum / burst.size).toDouble()).toInt()
                    AudioVisualizer.feed(burst, tx = true)
                    onMicFrame(burst)
                    micPos = 0
                }
            }
        }
    }

    fun startPlayback() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, BURST_SAMPLES * 2 * 6)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        running = true
        playbackThread = Thread({ playbackLoop() }, "dmr-playback").apply { start() }
    }

    /** Queue a decoded downstream burst (480 samples). */
    fun submitRx(burst: ShortArray) {
        jitter.put(burst)
    }

    private fun playbackLoop() {
        var lastWriteMs = 0L
        while (running) {
            val frame = jitter.poll()
            if (frame == null) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            rxAgc.process(frame)
            AudioVisualizer.feed(frame, tx = false)
            val now = System.currentTimeMillis()
            // Fade in only at the start of a new talkspurt (gap > 180 ms)
            if (lastWriteMs == 0L || now - lastWriteMs > 180) {
                Envelope.fadeIn(frame, 40)
            }
            lastWriteMs = now
            track?.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    fun playbackDepth(): Int = jitter.depth()

    @Synchronized
    fun stop() {
        running = false
        playbackThread?.interrupt()
        playbackThread?.join(500)
        playbackThread = null
        captureThread?.interrupt()
        captureThread?.join(500)
        captureThread = null
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        record = null
        track?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
        jitter.reset()
        txHp.reset()
        txAgc.reset()
        rxAgc.reset()
        micPos = 0
    }
}

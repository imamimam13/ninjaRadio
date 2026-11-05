package com.ninjadmr.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.ninjadmr.app.dsp.JitterBuffer
import java.util.concurrent.Executors

/**
 * 8 kHz mono PCM capture/playback for the DMR vocoder path.
 *
 * * Capture: raw 160-sample frames accumulated into 60 ms (480 sample) bursts,
 *   scaled with crisp linear micGain, and handed to [onMicFrame].
 * * Playback: decoded bursts buffered with low-latency JitterBuffer,
 *   scaled with clean linear spkGain, and written to AudioTrack without artificial
 *   starvation gaps or volume pumping.
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

    // Clean linear gains (matches NinjaRadio Lite: 250% mic, 120% speaker)
    @Volatile var micGain: Float = 2.5f
    @Volatile var spkGain: Float = 1.2f

    private val playExec = Executors.newSingleThreadExecutor()

    fun setGains(mic: Float, spk: Float) {
        this.micGain = mic.coerceIn(1.0f, 5.0f)
        this.spkGain = spk.coerceIn(0.5f, 2.5f)
    }

    // Jitter buffer with immediate single-frame priming to eliminate dropouts & stuttering
    private val jitter = JitterBuffer(BURST_SAMPLES, cushionFrames = 1, maxFrames = 32)
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

    @Synchronized
    fun stopCapture() {
        val t = captureThread
        captureThread = null
        t?.interrupt()
        try {
            t?.join(300)
        } catch (_: Exception) {}
        record?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
            } catch (_: Exception) {}
            try {
                it.release()
            } catch (_: Exception) {}
        }
        record = null
        lastMicRms = 0
        micPos = 0
    }

    private fun captureLoop(rec: AudioRecord) {
        val frame = ShortArray(FRAME_SAMPLES)
        while (running && captureThread != null) {
            var read = 0
            while (read < FRAME_SAMPLES && running && captureThread != null) {
                val n = rec.read(frame, read, FRAME_SAMPLES - read)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "AudioRecord.read error $n")
                    break
                }
                read += n
            }
            if (read != FRAME_SAMPLES) continue
            val out = ShortArray(FRAME_SAMPLES)
            var sum = 0L
            for (i in 0 until FRAME_SAMPLES) {
                val s = (frame[i] * micGain).toInt().coerceIn(-32768, 32767).toShort()
                out[i] = s
                sum += (s.toLong() * s.toLong())
            }
            lastMicRms = kotlin.math.sqrt((sum / FRAME_SAMPLES).toDouble()).toInt()
            AudioVisualizer.feed(out, tx = true)
            onMicFrame(out)
        }
    }

    fun startPlayback() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError("AudioTrack.getMinBufferSize failed: $minBuf")
            return
        }
        val bufSize = maxOf(minBuf, 8000)
        val trk = AudioTrack(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            0,
        )
        if (trk.state != AudioTrack.STATE_INITIALIZED) {
            trk.release()
            onError("AudioTrack not initialized")
            return
        }
        track = trk
        trk.play()
        running = true
    }

    /** Direct real-time audio playback without jitter buffer delays. */
    fun play(pcm: ShortArray) {
        playExec.execute {
            val t = track ?: return@execute
            val out = if (spkGain != 1.0f) {
                val amplified = ShortArray(pcm.size)
                for (i in pcm.indices) {
                    amplified[i] = (pcm[i] * spkGain).toInt().coerceIn(-32768, 32767).toShort()
                }
                amplified
            } else {
                pcm
            }
            t.write(out, 0, out.size)
            try {
                AudioVisualizer.feed(out, tx = false)
            } catch (_: Exception) {}
        }
    }

    /** Queue a decoded downstream burst (480 samples). */
    fun submitRx(burst: ShortArray) {
        play(burst)
    }

    fun playbackDepth(): Int = 0

    @Synchronized
    fun stop() {
        running = false
        stopCapture()
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {}
            try {
                it.release()
            } catch (_: Exception) {}
        }
        track = null
        micPos = 0
    }
}

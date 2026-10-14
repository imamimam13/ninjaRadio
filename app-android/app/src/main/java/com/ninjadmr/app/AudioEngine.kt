package com.ninjadmr.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.ninjadmr.app.dsp.Agc
import com.ninjadmr.app.dsp.AudioDsp
import com.ninjadmr.app.dsp.Envelope
import com.ninjadmr.app.dsp.JitterBuffer

/**
 * 8 kHz mono PCM capture/playback for the DMR vocoder path.
 *
 * * Capture: frames are accumulated into 60 ms (480 sample) bursts, AGC'd and
 *   handed to [onMicFrame].
 * * Playback: decoded bursts are pushed through a [JitterBuffer], band-limited
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

    private val txAgc = Agc()
    private val rxAgc = Agc()
    private val rxDsp = AudioDsp(sampleRate.toDouble())
    private val jitter = JitterBuffer(BURST_SAMPLES)
    private val micAccum = ShortArray(BURST_SAMPLES)
    private var micPos = 0

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
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (exc: Exception) {
            onError("AudioRecord init failed: ${exc.message}")
            return
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
                    txAgc.process(burst)
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
        val bufSize = maxOf(minBuf, BURST_SAMPLES * 2 * 4)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                Thread.sleep(5)
                continue
            }
            rxDsp.processRx(frame)
            rxAgc.process(frame)
            val now = System.currentTimeMillis()
            // Only ramp in when a new burst starts (after a gap), never on the
            // first samples of every frame - that would click every 60 ms.
            if (lastWriteMs == 0L || now - lastWriteMs > 150) {
                Envelope.fadeIn(frame, 4)
            }
            lastWriteMs = now
            track?.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    fun playbackDepth(): Int = jitter.depth()

    @Synchronized
    fun stop() {
        running = false
        playbackThread?.join(500)
        playbackThread = null
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
        rxDsp.reset()
        txAgc.reset()
        rxAgc.reset()
        micPos = 0
    }
}

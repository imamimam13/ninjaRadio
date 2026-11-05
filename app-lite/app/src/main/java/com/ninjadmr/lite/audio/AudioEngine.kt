package com.ninjadmr.lite.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class AudioEngine {
    companion object {
        private const val TAG = "AudioEngine"

        /**
         * Checks if a 3.5mm TRRS headset / HT interface cable or USB audio input is connected.
         * Compatible with Android 5.0+ (API 21+).
         */
        @Suppress("DEPRECATION")
        fun isExternalAudioConnected(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                for (d in inputs) {
                    when (d.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_LINE_ANALOG,
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return true
                    }
                }
                val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (d in outputs) {
                    when (d.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> return true
                    }
                }
            }
            return try {
                am.isWiredHeadsetOn
            } catch (_: Exception) {
                false
            }
        }
    }

    private val rate = 8000
    private var track: AudioTrack? = null
    private val playExec = Executors.newSingleThreadExecutor()

    @Volatile private var capturing = false
    private var capThread: Thread? = null

    @Volatile var micGain: Float = 2.5f
    @Volatile var spkGain: Float = 1.2f
    @Volatile var masterVolume: Float = 1.0f

    fun configure(micGain: Float, spkGain: Float) {
        this.micGain = micGain.coerceIn(0.10f, 5.0f)
        this.spkGain = spkGain.coerceIn(0.20f, 2.5f)
    }

    fun startPlayback(context: Context? = null) {
        if (track != null) return
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = (if (min > 0) min else 8000).coerceAtLeast(8000)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        try {
            val it = AudioTrack(attrs, fmt, bufSize, AudioTrack.MODE_STREAM, 0)
            if (it.state == AudioTrack.STATE_INITIALIZED) {
                it.setVolume(masterVolume)
                it.play()
                track = it
            } else {
                Log.e(TAG, "AudioTrack failed to initialize (state=${it.state})")
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPlayback failed: ${e.message}", e)
        }
    }

    fun stopPlayback() {
        try {
            track?.stop()
        } catch (_: Exception) {}
        try {
            track?.release()
        } catch (_: Exception) {}
        track = null
    }

    fun play(pcm: ShortArray) {
        playExec.execute {
            val t = track ?: return@execute
            if (spkGain == 1.0f) {
                t.write(pcm, 0, pcm.size)
            } else {
                val out = ShortArray(pcm.size)
                for (i in pcm.indices) {
                    val s = (pcm[i] * spkGain).toInt().coerceIn(-32768, 32767)
                    out[i] = s.toShort()
                }
                t.write(out, 0, out.size)
            }
        }
    }

    private fun routeToExternalHeadsetIfAvailable(context: Context?, rec: AudioRecord) {
        if (context == null) return
        if (!AudioPreferences.isHeadsetPriority(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                // Priority: Wired Headset TRRS (HT cable) -> Line Analog -> USB Audio
                val preferred = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                    ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_LINE_ANALOG }
                    ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                if (preferred != null) {
                    rec.setPreferredDevice(preferred)
                    Log.i(TAG, "Routed AudioRecord input to external jack device type=${preferred.type}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "setPreferredDevice failed: ${e.message}")
            }
        }
    }

    fun startCapture(context: Context? = null, cb: (ShortArray) -> Unit): Boolean {
        if (capturing) return true
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = min.coerceAtLeast(3200)
        Log.i(TAG, "startCapture() rate=$rate, bufSize=$bufSize")

        // Prefer DEFAULT / MIC directly on legacy Android / MSM8909 POC devices and wired headset jack
        var rec: AudioRecord? = null
        val sourcesToTry = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )

        for (src in sourcesToTry) {
            try {
                val candidate = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    rec = candidate
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $src failed: ${e.message}")
            }
        }

        val finalRec = rec ?: return false
        routeToExternalHeadsetIfAvailable(context, finalRec)

        try {
            finalRec.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "rec.startRecording() threw exception: ${e.message}", e)
            try { finalRec.release() } catch (_: Exception) {}
            return false
        }

        capturing = true
        Log.i(TAG, "AudioRecord started successfully! recordingState=${finalRec.recordingState}")

        capThread = thread(name = "mic-capture", isDaemon = true) {
            val buf = ShortArray(160)
            while (capturing) {
                var n = 0
                while (n < 160 && capturing) {
                    val r = finalRec.read(buf, n, 160 - n)
                    if (r <= 0) break
                    n += r
                }
                if (n == 160) {
                    val out = ShortArray(160)
                    val g = micGain
                    for (i in 0 until 160) {
                        out[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    cb(out)
                } else if (n <= 0) {
                    Log.w(TAG, "AudioRecord read returned $n, exiting capture thread")
                    break
                }
            }
            try {
                finalRec.stop()
            } catch (_: Exception) {}
            finalRec.release()
            Log.i(TAG, "mic-capture thread finished")
        }
        return true
    }

    fun stopCapture() {
        capturing = false
        capThread?.interrupt()
        capThread = null
    }
}

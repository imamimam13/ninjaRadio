package com.ninjadmr.pwa

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isHardwarePttDown = false
    private var defaultServerOrigin = "https://43.173.13.117.sslip.io"

    private val pttKeyCodes = setOf(
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        138, // KEY_PTT
        142, // F12 PoC
        226, // MTK PTT
        228, // Qualcomm PTT
        261, // F4
        262, // F5
        263, // F6
        288, // PoC Side 1
        289, // PoC Side 2
        290, // PoC Key
        291, // CARAKA-22 / F22 Hardware PTT
        292  // PoC Aux PTT
    )

    private val pttBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val isDown = action.contains("down", ignoreCase = true) ||
                action.contains("start", ignoreCase = true) ||
                action.endsWith(". PRESS")
            if (isDown) {
                triggerWebPtt(true, "BROADCAST:$action")
            } else {
                triggerWebPtt(false, "BROADCAST:$action")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#05070c")
        window.navigationBarColor = Color.parseColor("#05070c")

        val prefs = getSharedPreferences("ninjaradio_pwa", Context.MODE_PRIVATE)
        defaultServerOrigin = prefs.getString("server_origin", "https://43.173.13.117.sslip.io")
            ?: "https://43.173.13.117.sslip.io"

        setupAudioRouting()
        requestAudioPermissionsIfNeeded()
        registerPttBroadcasts()

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#05070c"))
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(NinjaJsBridge(), "NinjaAndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // Allow sslip.io / self-signed repeater certificates
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }

        loadEmbeddedPwa()
    }

    private fun loadEmbeddedPwa() {
        try {
            val html = assets.open("index.html").bufferedReader().use { it.readText() }
            webView.loadDataWithBaseURL(
                "$defaultServerOrigin/radio",
                html,
                "text/html",
                "UTF-8",
                null
            )
        } catch (e: Exception) {
            webView.loadUrl("$defaultServerOrigin/radio")
        }
    }

    private fun setupAudioRouting() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = true
        } catch (_: Exception) {}
    }

    private fun requestAudioPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    private fun registerPttBroadcasts() {
        val filter = IntentFilter().apply {
            addAction("com.android.ptt.keydown")
            addAction("com.android.ptt.keyup")
            addAction("android.intent.action.PTT.down")
            addAction("android.intent.action.PTT.up")
            addAction("com.poc.ptt.down")
            addAction("com.poc.ptt.up")
            addAction("com.zello.ptt.down")
            addAction("com.zello.ptt.up")
            addAction("com.inrico.ptt.down")
            addAction("com.inrico.ptt.up")
            addAction("com.kydera.ptt.down")
            addAction("com.kydera.ptt.up")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pttBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pttBroadcastReceiver, filter)
        }
    }

    private fun triggerWebPtt(down: Boolean, keyLabel: String) {
        runOnUiThread {
            val eventName = if (down) "ninja-ptt-down" else "ninja-ptt-up"
            val js = """
                (function() {
                  window.dispatchEvent(new CustomEvent('$eventName', { detail: { key: '$keyLabel' } }));
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (pttKeyCodes.contains(code)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!isHardwarePttDown && event.repeatCount == 0) {
                    isHardwarePttDown = true
                    triggerWebPtt(true, "KEYCODE_$code")
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (isHardwarePttDown) {
                    isHardwarePttDown = false
                    triggerWebPtt(false, "KEYCODE_$code")
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pttBroadcastReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    inner class NinjaJsBridge {
        @JavascriptInterface
        fun openCloudUrl(url: String) {
            runOnUiThread {
                webView.loadUrl(url)
            }
        }

        @JavascriptInterface
        fun openEmbeddedPwa() {
            runOnUiThread {
                loadEmbeddedPwa()
            }
        }

        @JavascriptInterface
        fun setServerOrigin(origin: String) {
            val cleaned = origin.trim().trimEnd('/')
            defaultServerOrigin = cleaned
            getSharedPreferences("ninjaradio_pwa", Context.MODE_PRIVATE)
                .edit()
                .putString("server_origin", cleaned)
                .apply()
            runOnUiThread {
                loadEmbeddedPwa()
            }
        }

        @JavascriptInterface
        fun getServerOrigin(): String {
            return defaultServerOrigin
        }

        @JavascriptInterface
        fun vibrate(ms: Long) {
            try {
                val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(ms)
                }
            } catch (_: Exception) {}
        }
    }
}

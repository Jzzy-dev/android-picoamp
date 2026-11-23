// PicoAmp for Android (Google TV first, phones and tablets too): PicoAmp for the web in a
// full-screen WebView, from the app's own assets. What the app adds:
//
//  * the TV's remote: the arrows and OK go to the page as they go (picoamp.tvKey: pressed, held
//    on, let go: the page tells a press from a key held), Back and the media keys as
//    picoamp.tv(action); Back at the top of the list (or the page's Exit) leaves the app
//  * the volume: the device's (the TV's, by HDMI-CEC when the device's own is fixed) is PicoAmp's:
//    shown on the player and set from it
//  * /relay/?url=: the page's relay, here in the app (no other site's CORS rules apply to an app),
//    for the song titles of stations, the markets and the news
//  * /sonos/...: the Sonos bridge (as PicoAmp for Windows has it), here in the app: the rooms of the
//    network and their keys, without a PC
//
// The page is https://appassets.androidplatform.net/assets/web/index.html: a secure page of its
// own origin, so its storage (settings, lists, logos) stays.
package nl.jzzy.picoamp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.provider.Settings
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var volumeSeen = -1
    private val volumeWatch = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = volumeChanged()
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(0xFF000049.toInt())
        setContentView(web)
        immersive()

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW     // http stations, a PC's bridge
        s.allowFileAccess = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        val browserAgent = s.userAgentString

        val assets = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val relay = Relay(browserAgent)
        val sonos = SonosBridge(this)
        val net = NetPictures(this)

        web.webChromeClient = WebChromeClient()          // (prompt(): the search's text)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != HOST) return null
                val path = u.path ?: ""
                return when {
                    path.startsWith("/relay") -> relay.handle(u)
                    path.startsWith("/sonos/") -> sonos.handle(u)
                    path.startsWith("/net/") -> net.handle(u)
                    path.endsWith(".wasm") -> assets.shouldInterceptRequest(u)?.also { it.mimeType = "application/wasm" }
                    else -> assets.shouldInterceptRequest(u)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != HOST                 // (the page stays; links elsewhere are not followed)
        }
        web.addJavascriptInterface(Bridge(), "PicoAmpAndroid")

        val tvMode = isTv()
        val q = StringBuilder("?relay=").append(enc("$ORIGIN/relay/")).append("&bridge=").append(enc(ORIGIN))
        if (tvMode) q.append("&tv=1")
        if (savedInstanceState == null) web.loadUrl("$ORIGIN/assets/web/index.html$q")
        else web.restoreState(savedInstanceState)
        web.requestFocus()

        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeWatch)
    }

    // ---------------------------------------------------------------- the volume
    private fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max <= 0) 100 else Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
    }

    private fun volumeChanged() {
        if (audio.isVolumeFixed) return
        val v = volumePercent()
        if (v == volumeSeen) return
        volumeSeen = v
        web.evaluateJavascript("window.picoamp && picoamp.systemVolume($v)", null)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun isTv(): Boolean {
        val m = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return m.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // (the page is not paused when the app goes to the background: the music plays on)
    override fun onDestroy() {
        web.evaluateJavascript("window.picoamp && picoamp.save()", null)
        contentResolver.unregisterContentObserver(volumeWatch)
        web.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        web.evaluateJavascript("window.picoamp && picoamp.save()", null)
        super.onStop()
    }

    // ---------------------------------------------------------------- the remote
    private fun tv(action: String, then: ((String) -> Unit)? = null) {
        web.evaluateJavascript("window.picoamp ? picoamp.tv('$action') : 'ok'") { r -> then?.invoke(r.trim('"')) }
    }

    // the arrows and OK: 'down', 'repeat' (held on) and 'up' (let go); 'exit': the page's Exit
    private fun tvKey(phase: String, key: String) {
        web.evaluateJavascript("window.picoamp && picoamp.tvKey ? picoamp.tvKey('$phase', '$key') : 'ok'") { r ->
            if (r.trim('"') == "exit") finish()
        }
    }

    private fun dpadOf(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "ok"
        else -> null
    }

    private fun actionOf(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "playpause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
        KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> "next"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> "prev"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "ff"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "rew"
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> "menu"
        KeyEvent.KEYCODE_INFO -> "info"
        else -> null
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        // the arrows and OK (a keyboard's Enter too, not the on-screen keyboard's): as they go
        val pad = dpadOf(code) ?: if (code == KeyEvent.KEYCODE_ENTER && e.device?.isVirtual != true) "ok" else null
        if (pad != null) {
            when {
                e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 -> tvKey("down", pad)
                e.action == KeyEvent.ACTION_DOWN -> tvKey("repeat", pad)
                e.action == KeyEvent.ACTION_UP -> tvKey("up", pad)
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.action == KeyEvent.ACTION_UP) tv("back") { if (it == "exit") finish() }
            return true
        }
        val a = actionOf(code)
        if (a != null) {
            if (e.action == KeyEvent.ACTION_DOWN) tv(a)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- the page's calls
    inner class Bridge {
        @JavascriptInterface fun exit() { ui.post { finish() } }
        @JavascriptInterface fun isTv(): Boolean = this@MainActivity.isTv()

        // the volume: the device's, 0-100; fixed (a TV's by HDMI-CEC): only up or down
        @JavascriptInterface fun getVolume(): Int = volumePercent().also { volumeSeen = it }
        @JavascriptInterface fun volumeFixed(): Boolean = audio.isVolumeFixed
        @JavascriptInterface fun setVolume(v: Int) {
            ui.post {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val i = Math.round(v.coerceIn(0, 100) * max / 100f)
                volumeSeen = if (max <= 0) 100 else Math.round(i * 100f / max)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, i, AudioManager.FLAG_SHOW_UI)
            }
        }
        @JavascriptInterface fun volumeStep(d: Int) {
            ui.post {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (d > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
        }
    }
}

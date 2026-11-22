// PicoAmp for Android (Google TV first, phones and tablets too): PicoAmp for the web in a
// full-screen WebView, from the app's own assets. What the app adds:
//
//  * the TV's remote: the arrows, OK (held: PicoAmp's menu), Back and the media keys go to the
//    page as picoamp.tv(action); Back at the top of the list leaves the app
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
    private var okDown = false
    private var okLong = false
    private val okHeld = Runnable { okLong = true; tv("longok") }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        const val HOLD_MS = 600L
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

        web.webChromeClient = WebChromeClient()          // (prompt(): the search's text)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != HOST) return null
                val path = u.path ?: ""
                return when {
                    path.startsWith("/relay") -> relay.handle(u)
                    path.startsWith("/sonos/") -> sonos.handle(u)
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

    private fun actionOf(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
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
        // a keyboard's letters, digits and the like: to the page as they are
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || (code == KeyEvent.KEYCODE_ENTER && e.device?.isVirtual != true) ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            // OK: a press is Enter, held it opens the menu
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && !okDown) {
                okDown = true
                okLong = false
                ui.postDelayed(okHeld, HOLD_MS)
            } else if (e.action == KeyEvent.ACTION_UP) {
                ui.removeCallbacks(okHeld)
                if (okDown && !okLong) tv("ok")
                okDown = false
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
    }
}

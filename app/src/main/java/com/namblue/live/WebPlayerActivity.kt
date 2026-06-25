package com.namblue.live

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.namblue.live.databinding.ActivityWebPlayerBinding

/**
 * Watches a TikTok live page in a WebView, driven by a TV remote.
 *
 * Used for age/sensitive-gated lives that TikTok won't serve anonymously: the native path can't
 * resolve those, so we hand off to TikTok's own web player where the user logs in + confirms age.
 *
 * The web page isn't D-pad-navigable, so an on-screen pointer is driven with the remote
 * (arrows move it, OK taps, top/bottom edges scroll). JavaScript autoplay is on and HTML5
 * video can go fullscreen. Cookies persist, so any login is only done once — after a TikTok
 * login, the native resolver reuses those cookies and can play gated lives natively.
 */
class WebPlayerActivity : android.app.Activity() {

    private lateinit var binding: ActivityWebPlayerBinding

    // Holder for an HTML5 video that requests fullscreen.
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = TikTokLiveResolver.USER_AGENT
            webViewClient = statusWebViewClient()
            webChromeClient = fullscreenChromeClient()
        }

        if (savedInstanceState == null) {
            binding.webView.loadUrl(intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL)
        }

        binding.root.post { centerCursor() }
    }

    // --- D-pad pointer ----------------------------------------------------------------------
    //
    // The web page is not D-pad-navigable, so we drive an on-screen pointer with the remote
    // (like a TV browser): arrows move it, OK taps the page at the pointer, and pushing past
    // the top/bottom edge scrolls. This closes the "log in"/warning card (the X / Continue)
    // and clicks into the live video without a touchscreen.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While an HTML5 video is fullscreen, let keys flow to the player instead.
        if (customView == null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    if (event.action == KeyEvent.ACTION_DOWN) moveCursor(event.keyCode, event.repeatCount)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    if (event.action == KeyEvent.ACTION_UP) tapAtCursor()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun centerCursor() {
        binding.cursor.apply {
            x = binding.root.width / 2f
            y = binding.root.height / 2f
            visibility = View.VISIBLE
        }
    }

    private fun moveCursor(keyCode: Int, repeatCount: Int) {
        if (binding.cursor.visibility != View.VISIBLE) centerCursor()
        val step = CURSOR_STEP + minOf(repeatCount * CURSOR_ACCEL, CURSOR_STEP_MAX)
        var nx = binding.cursor.x
        var ny = binding.cursor.y
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> nx -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> nx += step
            KeyEvent.KEYCODE_DPAD_UP -> ny -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> ny += step
        }
        val maxX = (binding.root.width - CURSOR_MARGIN).toFloat()
        val maxY = (binding.root.height - CURSOR_MARGIN).toFloat()
        // Scroll the page when pushing past the vertical edge.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && ny < 0f) binding.webView.scrollBy(0, -step.toInt())
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && ny > maxY) binding.webView.scrollBy(0, step.toInt())
        binding.cursor.x = nx.coerceIn(0f, maxX)
        binding.cursor.y = ny.coerceIn(0f, maxY)
    }

    private fun tapAtCursor() {
        if (binding.cursor.visibility != View.VISIBLE) centerCursor()
        val x = binding.cursor.x + CURSOR_TIP
        val y = binding.cursor.y + CURSOR_TIP
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            .also { binding.webView.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(now, now + 12, MotionEvent.ACTION_UP, x, y, 0)
            .also { binding.webView.dispatchTouchEvent(it); it.recycle() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Activity but still the simplest way to support WebView back-nav")
    override fun onBackPressed() {
        when {
            customView != null -> binding.webView.webChromeClient?.onHideCustomView()
            binding.webView.canGoBack() -> binding.webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()           // stop the video/audio when backgrounded
        CookieManager.getInstance().flush()  // persist the login session to disk
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()  // make sure the login session is saved
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    private fun fullscreenChromeClient() = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            binding.webView.visibility = View.GONE
            binding.root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            goFullscreen()
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            binding.root.removeView(view)
            customView = null
            binding.webView.visibility = View.VISIBLE
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            goFullscreen()
        }
    }

    private fun statusWebViewClient() = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            Log.i(TAG, "page started: $url")
            showStatus(getString(R.string.status_loading_web))
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.i(TAG, "page finished: $url  title=${view?.title}")
            showStatus(null)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                Log.w(TAG, "main-frame error: ${error?.errorCode} ${error?.description}")
                showStatus(getString(R.string.status_web_error))
            }
        }
    }

    private fun showStatus(text: String?) {
        binding.statusText.apply {
            if (text == null) {
                visibility = View.GONE
            } else {
                this.text = text
                visibility = View.VISIBLE
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun goFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    companion object {
        private const val TAG = "WebPlayer"
        const val EXTRA_URL = "url"
        private const val DEFAULT_URL = "https://www.tiktok.com/@namblueraudua/live"

        // Pointer movement tuning (pixels).
        private const val CURSOR_STEP = 55f          // base move per key press
        private const val CURSOR_ACCEL = 14f         // extra per repeat (holding a direction)
        private const val CURSOR_STEP_MAX = 260f     // cap on the accelerated step
        private const val CURSOR_MARGIN = 8          // keep the tip on-screen
        private const val CURSOR_TIP = 4f            // offset of the arrow tip from the view origin

        fun intent(context: Context, url: String): Intent =
            Intent(context, WebPlayerActivity::class.java).putExtra(EXTRA_URL, url)
    }
}

package com.namblue.live

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

/**
 * Drives the invisible WebView that reads TikTok pages — the live feed ([LiveDirectory]) and
 * profiles ([ProfileDirectory]) — since TikTok's list APIs only answer its own page scripts.
 *
 * The page's own video previews are blocked at the network layer: nobody sees them, and on a
 * TV they would compete with the real preview for bandwidth and the hardware decoder.
 */
class LivePageReader(private val webView: WebView) {

    private var pageFinished: CompletableDeferred<Unit>? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun setUp() {
        // Shares cookies with the web player, so a TikTok login there unlocks search here.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
            userAgentString = TikTokLiveResolver.USER_AGENT
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url != BLANK_PAGE) pageFinished?.complete(Unit)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (isVideoStream(request.url.toString())) emptyResponse() else null
        }
    }

    /**
     * Run [script] at the start of every tiktok.com page, before the page's own scripts.
     * Returns false on WebViews too old to support it.
     */
    fun addStartScript(script: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(TIKTOK_ORIGIN))
        return true
    }

    /** Load [url] and suspend until it has finished loading (its cards render afterwards). */
    suspend fun open(url: String) {
        val finished = CompletableDeferred<Unit>().also { pageFinished = it }
        webView.loadUrl(url)
        finished.await()
    }

    /** Raw output of [script] (by default [LiveDirectory.SCRAPE_JS]) for what the page shows now. */
    suspend fun read(script: String = LiveDirectory.SCRAPE_JS): String? {
        val result = CompletableDeferred<String?>()
        webView.evaluateJavascript(script) { result.complete(it) }
        return result.await()
    }

    /** Scroll every scrollable pane to its end so TikTok appends more cards, then give it time. */
    suspend fun scrollForMore() {
        webView.evaluateJavascript(SCROLL_JS, null)
        delay(SCROLL_SETTLE_MS)
    }

    fun isLoggedIn(): Boolean = LiveDirectory.isLoggedIn(CookieManager.getInstance().getCookie(TIKTOK_ORIGIN))

    fun pause() = webView.onPause()

    fun resume() = webView.onResume()

    fun destroy() {
        webView.loadUrl(BLANK_PAGE)
        webView.destroy()
    }

    private fun isVideoStream(url: String): Boolean {
        val path = url.substringBefore('?')
        return url.contains("://pull-") || VIDEO_SUFFIXES.any { path.endsWith(it) }
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private companion object {
        const val BLANK_PAGE = "about:blank"
        const val TIKTOK_ORIGIN = "https://www.tiktok.com"
        const val SCROLL_SETTLE_MS = 1_800L
        val VIDEO_SUFFIXES = listOf(".flv", ".m3u8", ".ts", ".mp4", ".m4s", ".mpd")

        const val SCROLL_JS = """
            (function () {
              var panes = [document.scrollingElement];
              document.querySelectorAll('div').forEach(function (el) {
                if (el.scrollHeight > el.clientHeight + 50 &&
                    /(auto|scroll)/.test(getComputedStyle(el).overflowY)) panes.push(el);
              });
              panes.forEach(function (el) { if (el) el.scrollTop = el.scrollHeight; });
            })()
        """
    }
}

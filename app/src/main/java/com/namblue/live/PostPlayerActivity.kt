package com.namblue.live

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import androidx.media3.common.util.UnstableApi
import com.namblue.live.databinding.ActivityPostPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Plays a creator's posts one after another, TikTok-style: DOWN/UP = next/previous post,
 * OK = pause/play, BACK = return to the grid. LEFT/RIGHT flip through the pictures of a photo
 * post, and move between posts on a video. A finished post moves on to the next by itself.
 *
 * Videos play in TikTok's official embed player, hosted in an iframe of a tiny local page so the
 * app can steer it through the player's postMessage API and hear its state changes. Photo albums
 * whose pictures are known (logged-in post list) are shown by [AlbumPlayer] instead, because the
 * embed player's carousel can't be paused.
 */
@UnstableApi
class PostPlayerActivity : Activity() {

    private lateinit var binding: ActivityPostPlayerBinding
    private lateinit var ids: List<String>
    private lateinit var photoIds: Set<String>
    private var postsById: Map<String, ProfilePost> = emptyMap()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var album: AlbumPlayer

    /** True while the current post is an album shown by [album] rather than the web player. */
    private var albumMode = false
    private var index = 0
    /**
     * Toggled by OK. Posts autoplay, so it starts false and the first OK always pauses —
     * whatever the player reports meanwhile (buffering, playing). While set, "⏸" stays on screen.
     */
    private var pausedByUser = false
    private val hideOverlay = Runnable { binding.overlay.visibility = View.GONE }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ids = intent.getStringArrayListExtra(EXTRA_IDS).orEmpty()
        photoIds = intent.getStringArrayListExtra(EXTRA_PHOTO_IDS).orEmpty().toSet()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        if (ids.isEmpty()) {
            finish()
            return
        }
        postsById = PostQueue.posts.associateBy { it.id }
        album = AlbumPlayer(
            context = this,
            imageView = binding.albumImage,
            scope = uiScope,
            targetPx = resources.displayMetrics.heightPixels,
            onPicture = ::onAlbumPicture,
            onFinished = { step(+1) },
        )
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.playerWeb, true)
        binding.playerWeb.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = TikTokLiveResolver.USER_AGENT
            webChromeClient = WebChromeClient()
            // Only receives the embed player's state numbers; nothing sensitive is exposed.
            addJavascriptInterface(PlayerBridge(), BRIDGE_NAME)
        }
        show(index)
    }

    override fun onPause() {
        binding.playerWeb.onPause()
        if (albumMode) album.setPaused(true)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.playerWeb.onResume()
        if (albumMode) album.setPaused(pausedByUser)
    }

    override fun onDestroy() {
        if (::album.isInitialized) album.stop()
        uiScope.cancel()
        binding.overlay.removeCallbacks(hideOverlay)
        binding.playerWeb.loadUrl(BLANK_PAGE)
        binding.playerWeb.destroy()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (isDown) step(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (isDown) step(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDown) sideways(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isDown) sideways(-1)
                return true
            }
            // TV remotes send OK as any of these; some also have dedicated play/pause keys.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> {
                if (event.action == KeyEvent.ACTION_UP) setPaused(!pausedByUser)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_UP) setPaused(true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event.action == KeyEvent.ACTION_UP) setPaused(false)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A photo post flips its pictures; anything else moves between posts. */
    private fun sideways(delta: Int) {
        when {
            albumMode -> album.flip(delta)
            ids[index] in photoIds -> flipPhoto(delta)
            else -> step(delta)
        }
    }

    /**
     * The carousel lives in TikTok's cross-origin iframe, so it can't be scripted; tap its own
     * ‹ › arrows instead, which sit at the vertical middle of the left and right edges.
     */
    private fun flipPhoto(delta: Int) {
        val web = binding.playerWeb
        val x = web.width * (if (delta > 0) PHOTO_NEXT_X else PHOTO_PREV_X)
        val y = web.height * PHOTO_ARROW_Y
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).also { web.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x, y, 0).also { web.dispatchTouchEvent(it); it.recycle() }
    }

    private fun step(delta: Int) {
        val target = index + delta
        if (target in ids.indices) show(target)
    }

    private fun show(position: Int) {
        index = position
        pausedByUser = false
        album.stop()
        val post = postsById[ids[position]]
        albumMode = post != null && post.kind == PostKind.PHOTO && post.images.isNotEmpty()
        if (albumMode && post != null) {
            binding.playerWeb.loadUrl(BLANK_PAGE)
            binding.playerWeb.visibility = View.INVISIBLE
            binding.albumImage.visibility = View.VISIBLE
            album.start(post)
        } else {
            binding.albumImage.visibility = View.GONE
            binding.playerWeb.visibility = View.VISIBLE
            binding.playerWeb.loadDataWithBaseURL(HOST_BASE, hostPage(ids[position]), "text/html", "utf-8", null)
        }
        val hint = if (ids[position] in photoIds) R.string.post_position_photo else R.string.post_position
        binding.overlay.text = getString(hint, position + 1, ids.size)
        binding.overlay.visibility = View.VISIBLE
        binding.overlay.removeCallbacks(hideOverlay)
        binding.overlay.postDelayed(hideOverlay, OVERLAY_MS)
    }

    /** Manual flips get a brief "Ảnh 2/6" so the viewer knows where they are in the album. */
    private fun onAlbumPicture(number: Int, total: Int) {
        if (pausedByUser || number == 1) return
        binding.overlay.removeCallbacks(hideOverlay)
        binding.overlay.text = getString(R.string.album_picture, number, total)
        binding.overlay.visibility = View.VISIBLE
        binding.overlay.postDelayed(hideOverlay, BADGE_MS)
    }

    private fun setPaused(pause: Boolean) {
        pausedByUser = pause
        if (albumMode) {
            album.setPaused(pause)
        } else {
            binding.playerWeb.evaluateJavascript("command('${if (pause) "pause" else "play"}')", null)
        }
        binding.overlay.removeCallbacks(hideOverlay)
        binding.overlay.text = getString(if (pause) R.string.post_paused else R.string.post_resumed)
        binding.overlay.visibility = View.VISIBLE
        // Paused: keep the badge up so it's obvious why nothing moves. Resumed: show it briefly.
        if (!pause) binding.overlay.postDelayed(hideOverlay, BADGE_MS)
    }

    private fun onPlayerState(state: Int) {
        if (state == STATE_ENDED) step(+1)
    }

    private inner class PlayerBridge {
        @JavascriptInterface
        fun onState(state: Int) {
            runOnUiThread { onPlayerState(state) }
        }
    }

    companion object {
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_PHOTO_IDS = "photo_ids"
        private const val BRIDGE_NAME = "NamBlueBridge"
        private const val HOST_BASE = "https://namblue.app/"
        private const val BLANK_PAGE = "about:blank"
        private const val OVERLAY_MS = 3_500L

        // Where the embed player draws a photo carousel's arrows (fractions of the player size).
        private const val PHOTO_PREV_X = 0.022f
        private const val PHOTO_NEXT_X = 0.978f
        private const val PHOTO_ARROW_Y = 0.5f
        private const val TAP_MS = 40L

        // Embed player states (TikTok player API).
        private const val STATE_ENDED = 0
        private const val BADGE_MS = 1_200L

        fun intent(context: Context, posts: List<ProfilePost>, index: Int): Intent =
            Intent(context, PostPlayerActivity::class.java)
                .also { PostQueue.posts = posts }
                .putStringArrayListExtra(EXTRA_IDS, ArrayList(posts.map { it.id }))
                .putStringArrayListExtra(EXTRA_PHOTO_IDS, ArrayList(posts.filter { it.kind == PostKind.PHOTO }.map { it.id }))
                .putExtra(EXTRA_INDEX, index)

        /** Full-screen iframe of the embed player, relaying its state changes to [BRIDGE_NAME]. */
        private fun hostPage(postId: String): String = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            iframe{border:0;width:100vw;height:100vh}</style></head><body>
            <iframe id="p" allow="autoplay; encrypted-media; fullscreen" src="${ProfileDirectory.playerUrl(postId)}"></iframe>
            <script>
              function command(type) {
                document.getElementById('p').contentWindow.postMessage({ type: type, 'x-tiktok-player': true }, '*');
              }
              window.addEventListener('message', function (e) {
                var d = e.data;
                if (!d || !d['x-tiktok-player']) return;
                // Embedded players start muted to be allowed to autoplay; a TV should have sound.
                if (d.type === 'onPlayerReady') { command('unMute'); command('play'); }
                if (d.type === 'onStateChange' && typeof d.value === 'number') $BRIDGE_NAME.onState(d.value);
              });
            </script></body></html>
        """.trimIndent()
    }
}

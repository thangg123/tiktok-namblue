package com.namblue.live

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.namblue.live.databinding.ActivityPlayerBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fullscreen native player for @namblueraudua's TikTok live. Open it, it resolves the stream,
 * plays it, then waits for playback to break (live ended / error) before re-resolving. When the
 * streamer is offline it shows a short status line and retries on an interval. Nothing else.
 */
@UnstableApi
class PlayerActivity : Activity() {

    private lateinit var binding: ActivityPlayerBinding

    /** TikTok handle to watch; defaults to NamBlue, overridable via [EXTRA_USERNAME]. */
    private val username: String by lazy {
        intent.getStringExtra(EXTRA_USERNAME)?.takeIf { it.isNotBlank() } ?: DEFAULT_USERNAME
    }

    // Reuse the WebView's TikTok login cookies so age/sensitive-gated lives resolve natively.
    private val resolver: TikTokLiveResolver by lazy {
        TikTokLiveResolver(
            uniqueId = username,
            cookieProvider = { CookieManager.getInstance().getCookie(TIKTOK_HOME) },
        )
    }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var loopJob: Job? = null

    /** Completed by the player listener when the current playback breaks, so the loop re-resolves. */
    private var interruption: CompletableDeferred<Unit>? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = signalInterruption()

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> showStatus(null)        // video is up → hide overlay
                Player.STATE_ENDED -> signalInterruption()    // live finished → reconnect
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()
    }

    override fun onStart() {
        super.onStart()
        createPlayer()
        startLoop()
    }

    override fun onStop() {
        super.onStop()
        loopJob?.cancel()
        loopJob = null
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    // --- Playback loop ----------------------------------------------------------------------

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = uiScope.launch {
            while (isActive) {
                showStatus(getString(R.string.status_connecting))
                when (val status = resolver.resolve()) {
                    is LiveStatus.Live -> {
                        val gate = CompletableDeferred<Unit>()
                        interruption = gate
                        play(status)
                        gate.await()                 // suspend until playback breaks
                        stopPlayback()
                        delay(RECONNECT_DELAY_MS)
                    }

                    LiveStatus.Offline -> {
                        stopPlayback()
                        showStatus(getString(R.string.status_waiting_live))
                        delay(RETRY_DELAY_MS)
                    }

                    is LiveStatus.Error -> {
                        stopPlayback()
                        showStatus(getString(R.string.status_reconnecting))
                        delay(RETRY_DELAY_MS)
                    }

                    // Live but TikTok gates it (age/sensitive): hand off to the WebView so the
                    // user can log in + confirm age. After that the resolver reuses those cookies
                    // and this native screen can play it directly next time.
                    LiveStatus.Restricted -> {
                        stopPlayback()
                        showStatus(getString(R.string.status_restricted))
                        startActivity(WebPlayerActivity.intent(this@PlayerActivity, "https://www.tiktok.com/@$username/live"))
                        finish()
                        return@launch
                    }
                }
            }
        }
    }

    private fun signalInterruption() {
        interruption?.takeIf { !it.isCompleted }?.complete(Unit)
    }

    private fun play(live: LiveStatus.Live) {
        val exo = player ?: return
        val builder = MediaItem.Builder().setUri(live.url)
        when (live.type) {
            StreamType.HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            StreamType.PROGRESSIVE -> { /* let ExoPlayer sniff (FLV/MP4) */ }
        }
        exo.setMediaItem(builder.build())
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun stopPlayback() {
        player?.run {
            stop()
            clearMediaItems()
        }
    }

    // --- Player lifecycle -------------------------------------------------------------------

    private fun createPlayer() {
        if (player != null) return
        // CDN pull hosts reject empty/unknown User-Agents, so use the same browser UA as the resolver.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(TikTokLiveResolver.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)

        // Keep the highest quality (origin) but play it as smoothly as possible on weak TV
        // hardware: a generous time-based buffer absorbs the high bitrate + network jitter, and
        // decoder fallback avoids a hard stall if the hardware decoder chokes on the source profile.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 6_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true) // buffer by time, not bytes — vital at high bitrate
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                addListener(playerListener)
            }

        binding.playerView.player = exo
        player = exo
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        binding.playerView.player = null
        player = null
    }

    // --- UI helpers -------------------------------------------------------------------------

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
        const val EXTRA_USERNAME = "username"
        private const val DEFAULT_USERNAME = "namblueraudua"
        private const val RETRY_DELAY_MS = 10_000L     // when offline / network error
        private const val RECONNECT_DELAY_MS = 2_000L  // brief pause after a live drops
        private const val TIKTOK_HOME = "https://www.tiktok.com"
    }
}

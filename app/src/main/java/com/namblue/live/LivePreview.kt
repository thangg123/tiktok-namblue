package com.namblue.live

import android.content.Context
import android.webkit.CookieManager
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays the channel the remote rests on in the browse screen's top pane, with sound, the way
 * YouTube on TV previews a video. Waiting [SETTLE_MS] before resolving keeps a fast scroll across
 * cards from firing a stream request per card.
 *
 * Previews use the quality TikTok lists (a quick-starting transcode); the full-screen player
 * still picks the best source when the channel is opened.
 */
@UnstableApi
class LivePreview(
    private val context: Context,
    private val playerView: PlayerView,
    private val scope: CoroutineScope,
    private val onState: (State) -> Unit,
    /** The previewed creator's profile, read from the same lookup that found the stream. */
    private val onProfile: (username: String, LiveProfile) -> Unit = { _, _ -> },
) {

    enum class State { LOADING, PLAYING, OFFLINE, RESTRICTED, FAILED }

    private var player: ExoPlayer? = null
    private var job: Job? = null
    private var current: String? = null
    private var retries = 0

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) onState(State.PLAYING)
        }

        // Live FLV connections drop now and then; reconnect a couple of times before giving up.
        override fun onPlayerError(error: PlaybackException) {
            val username = current ?: return
            if (retries >= MAX_RETRIES) {
                onState(State.FAILED)
                return
            }
            retries++
            onState(State.LOADING)
            job?.cancel()
            job = scope.launch {
                delay(RETRY_DELAY_MS)
                play(username)
            }
        }
    }

    fun start() {
        if (player != null) return
        player = LivePlayers.build(context, LivePlayers.previewLoadControl()).apply {
            playWhenReady = true
            addListener(listener)
        }
        playerView.player = player
    }

    fun stop() {
        job?.cancel()
        current = null
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        playerView.player = null
    }

    /** Preview [username]; ignored if it is already the one playing. */
    fun show(username: String) {
        if (username == current) return
        current = username
        retries = 0
        job?.cancel()
        player?.run {
            stop()
            clearMediaItems()
        }
        onState(State.LOADING)
        job = scope.launch {
            delay(SETTLE_MS)
            play(username)
        }
    }

    /** Resolve [username]'s stream (fresh signed URL each time) and start it. */
    private suspend fun play(username: String) {
        var profile: LiveProfile? = null
        val status = TikTokLiveResolver(
            uniqueId = username,
            cookieProvider = { CookieManager.getInstance().getCookie(TIKTOK_ORIGIN) },
            profileSink = { profile = it },
        ).resolve(allowSourceUpgrade = false)
        if (username != current) return // the cursor moved on while resolving
        profile?.let { onProfile(username, it) }
        when (status) {
            is LiveStatus.Live -> player?.run {
                setMediaItem(LivePlayers.mediaItem(status))
                prepare()
            }
            LiveStatus.Offline -> onState(State.OFFLINE)
            LiveStatus.Restricted -> onState(State.RESTRICTED)
            is LiveStatus.Error -> onState(State.FAILED)
        }
    }

    /** Forget the current channel so the same one can be previewed again (e.g. after returning). */
    fun reset() {
        current = null
    }

    private companion object {
        const val SETTLE_MS = 700L
        const val RETRY_DELAY_MS = 1_500L
        const val MAX_RETRIES = 2
        const val TIKTOK_ORIGIN = "https://www.tiktok.com"
    }
}

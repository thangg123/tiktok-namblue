package com.namblue.live

import android.content.Context
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows a photo album the way TikTok does — pictures one after another over the post's music —
 * but driven by the app, so it can actually be paused: TikTok's embed player keeps sliding its
 * carousel whatever it is told.
 */
@UnstableApi
class AlbumPlayer(
    private val context: Context,
    private val imageView: ImageView,
    private val scope: CoroutineScope,
    targetPx: Int,
    /** Called with (picture number from 1, total) whenever the picture changes. */
    private val onPicture: (Int, Int) -> Unit,
    /** Called after the last picture has been shown for its full time. */
    private val onFinished: () -> Unit,
) {

    private val images = AvatarLoader(targetPx, cacheEntries = IMAGE_CACHE)
    private var pictures: List<String> = emptyList()
    private var position = 0
    private var paused = false
    private var slideJob: Job? = null
    private var loadJob: Job? = null
    private var music: ExoPlayer? = null

    fun start(post: ProfilePost) {
        stop()
        pictures = post.images
        position = 0
        paused = false
        post.musicUrl?.let(::startMusic)
        showPicture()
    }

    /** Previous/next picture by hand; the slideshow carries on from there unless paused. */
    fun flip(delta: Int) {
        val target = position + delta
        if (target !in pictures.indices) return
        position = target
        showPicture()
    }

    fun setPaused(pause: Boolean) {
        paused = pause
        if (pause) {
            slideJob?.cancel()
            music?.pause()
        } else {
            music?.play()
            scheduleNext()
        }
    }

    fun stop() {
        slideJob?.cancel()
        loadJob?.cancel()
        music?.release()
        music = null
        imageView.setImageDrawable(null)
        pictures = emptyList()
    }

    private fun startMusic(url: String) {
        music = LivePlayers.build(context, LivePlayers.previewLoadControl()).apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    private fun showPicture() {
        onPicture(position + 1, pictures.size)
        loadJob?.cancel()
        val url = pictures.getOrNull(position) ?: return
        loadJob = scope.launch {
            images.load(url)?.let(imageView::setImageBitmap)
            // Warm the next one so the slide change is instant.
            pictures.getOrNull(position + 1)?.let { images.load(it) }
        }
        scheduleNext()
    }

    private fun scheduleNext() {
        slideJob?.cancel()
        if (paused) return
        slideJob = scope.launch {
            delay(SLIDE_MS)
            if (position + 1 < pictures.size) flip(+1) else onFinished()
        }
    }

    private companion object {
        const val SLIDE_MS = 3_500L
        const val IMAGE_CACHE = 6
    }
}

package com.namblue.live

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import com.namblue.live.flv.FlvExtractor

/** Builds the ExoPlayer setup TikTok live streams need; shared by the full player and the preview. */
@UnstableApi
object LivePlayers {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /**
     * Full-screen watching: a generous time-based buffer absorbs high-bitrate source streams and
     * network jitter on weak TV hardware.
     */
    fun watchingLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 60_000, 3_000, 6_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /** Previews: start as soon as a second of video is in, since the viewer is just browsing. */
    fun previewLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(4_000, 10_000, 1_000, 2_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    fun build(context: Context, loadControl: LoadControl): ExoPlayer {
        // CDN pull hosts reject empty/unknown User-Agents, so use the same browser UA as the resolver.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(TikTokLiveResolver.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)

        // Media3's own FLV extractor reads H.264 only; ours goes first and also reads the H.265
        // that TikTok serves for source-quality streams.
        val extractorsFactory = ExtractorsFactory {
            arrayOf<Extractor>(FlvExtractor()) + DefaultExtractorsFactory().createExtractors()
        }

        // Decoder fallback avoids a hard stall if the hardware decoder chokes on a stream profile.
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
    }

    fun mediaItem(live: LiveStatus.Live): MediaItem {
        val builder = MediaItem.Builder().setUri(live.url)
        when (live.type) {
            StreamType.HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            StreamType.PROGRESSIVE -> { /* let ExoPlayer sniff (FLV/MP4) */ }
        }
        return builder.build()
    }

    fun hasHevcDecoder(): Boolean = runCatching {
        MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, /* secure = */ false, /* tunneling = */ false).isNotEmpty()
    }.getOrDefault(false)
}

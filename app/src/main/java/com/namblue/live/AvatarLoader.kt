package com.namblue.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Downloads images (avatars, post covers, album pictures), downscaled to roughly [targetPx] and
 * kept in a small in-memory cache. Any failure returns null — the view keeps its placeholder.
 */
class AvatarLoader(private val targetPx: Int, cacheEntries: Int = CACHE_ENTRIES) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val cache = LruCache<String, Bitmap>(cacheEntries)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { runCatching { download(url) }.getOrNull() }
        bitmap?.let { cache.put(url, it) }
        return bitmap
    }

    private fun download(url: String): Bitmap? {
        val request = Request.Builder().url(url).header("User-Agent", TikTokLiveResolver.USER_AGENT).build()
        val bytes = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes()
        } ?: return null
        if (bytes.size > MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Largest power-of-two shrink that still keeps the image at least [targetPx] on its short side. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        val shortSide = minOf(width, height)
        while (shortSide / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val CACHE_ENTRIES = 80
        const val MAX_BYTES = 6 * 1024 * 1024
    }
}

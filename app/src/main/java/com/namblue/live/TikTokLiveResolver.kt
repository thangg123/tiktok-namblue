package com.namblue.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resolves a TikTok handle to a directly playable live-stream URL.
 *
 * Strategy (verified against live TikTok endpoints, no signing/msToken needed):
 *   1. GET api-live/user/room  -> data.user.roomId
 *   2. GET webcast/room/info   -> data.stream_url.hls_pull_url (.m3u8) / flv_pull_url {HD1,SD1}
 *   3. Fallback: scrape the /@user/live page for embedded stream JSON.
 *
 * Liveness is decided by the *presence of a non-empty stream URL*, not by the
 * unreliable integer `status` field. Playback URLs are signed/expiring, so callers
 * should resolve immediately before playing and re-resolve on failure.
 *
 * URL extraction is done by recursively walking the JSON for known stream keys, so
 * the resolver keeps working even if TikTok shuffles the surrounding object paths.
 */
class TikTokLiveResolver(
    private val uniqueId: String = "namblueraudua",
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun resolve(): LiveStatus = withContext(Dispatchers.IO) {
        runCatching {
            val roomId = fetchRoomId(uniqueId)
            if (roomId != null) {
                return@runCatching when (val viaApi = fetchStreamInfo(roomId)) {
                    is LiveStatus.Live -> viaApi
                    is LiveStatus.Error -> viaApi          // network/HTTP issue -> reconnecting
                    LiveStatus.Offline -> LiveStatus.Offline
                }
            }
            // No room id at all (never went live / handle changed) -> last-resort HTML scrape.
            when (val viaHtml = resolveFromHtml(uniqueId)) {
                is LiveStatus.Live -> viaHtml
                else -> LiveStatus.Offline
            }
        }.getOrElse { e ->
            LiveStatus.Error(e.message ?: "unknown")
        }
    }

    // --- Network steps (run inside resolve()'s IO context; OkHttp calls block) ---------------

    private fun fetchRoomId(uniqueId: String): String? {
        val url = "$API_LIVE_BASE?aid=$AID&sourceType=54&uniqueId=$uniqueId"
        val body = httpGet(url, referer = "https://www.tiktok.com/@$uniqueId/live") ?: return null
        return parseRoomId(body)
    }

    private fun fetchStreamInfo(roomId: String): LiveStatus {
        val url = "$WEBCAST_INFO_BASE?aid=$AID&room_id=$roomId"
        val body = httpGet(url, referer = "https://www.tiktok.com/") ?: return LiveStatus.Error("room/info HTTP error")
        return extractLive(body)
    }

    private fun resolveFromHtml(uniqueId: String): LiveStatus {
        val url = "https://www.tiktok.com/@$uniqueId/live"
        val html = httpGet(url, referer = "https://www.tiktok.com/") ?: return LiveStatus.Error("live page HTTP error")
        val json = extractEmbeddedJson(html) ?: return LiveStatus.Offline
        return extractLive(json)
    }

    /** Returns the body on 2xx, null on other status codes; rethrows real network failures. */
    private fun httpGet(url: String, referer: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val AID = "1988"
        private const val API_LIVE_BASE = "https://www.tiktok.com/api-live/user/room/"
        private const val WEBCAST_INFO_BASE = "https://webcast.tiktok.com/webcast/room/info/"

        /** Quality keys, highest first; matched case-insensitively. */
        private val QUALITY_PRIORITY =
            listOf("FULL_HD1", "HD2", "HD1", "SD2", "SD1", "ORIGIN", "ORIGION", "UHD", "HD", "SD", "LD")

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()

        /** Pull the roomId out of the api-live/user/room response. Pure; unit-tested. */
        fun parseRoomId(jsonText: String): String? = runCatching {
            val data = JSONObject(jsonText).optJSONObject("data") ?: return null
            val fromUser = data.optJSONObject("user")?.optString("roomId", "").orEmpty()
            val roomId = fromUser.ifBlank { data.optString("roomId", "") }
            roomId.takeIf { it.isNotBlank() && it != "0" }
        }.getOrNull()

        /**
         * Find a playable stream URL anywhere in [jsonText]. Pure; unit-tested.
         * Prefers HLS (.m3u8) over FLV. No URL found -> [LiveStatus.Offline].
         */
        fun extractLive(jsonText: String): LiveStatus = runCatching {
            val root = JSONTokener(jsonText).nextValue()
            // A 2xx body that isn't JSON (captcha/WAF/HTML interstitial) must not read as "offline".
            if (root !is JSONObject && root !is JSONArray) {
                return@runCatching LiveStatus.Error("non-JSON body")
            }
            // Prefer the richest source: the per-quality stream_data, picking "origin" (the raw
            // source) or, failing that, the highest resolution/bitrate. HLS is preferred per quality.
            bestFromStreamData(root)?.let { return@runCatching it }
            // Fallback: the default single URL / quality maps.
            val acc = StreamAcc()
            walk(root, acc)
            val hls = acc.hls
            val flv = acc.flv
            when {
                hls != null -> LiveStatus.Live(hls, StreamType.HLS)
                flv != null -> LiveStatus.Live(flv, StreamType.PROGRESSIVE)
                else -> LiveStatus.Offline
            }
        }.getOrElse { LiveStatus.Error("parse: ${it.message}") }

        /**
         * Choose the best quality out of TikTok's `stream_data` blob (the per-quality ladder,
         * often the only place "origin" exists). Returns null if no usable stream_data is found.
         */
        private fun bestFromStreamData(root: Any?): LiveStatus.Live? {
            val data = findStreamData(root)?.optJSONObject("data") ?: return null
            var best: Pick? = null
            val keys = data.keys()
            while (keys.hasNext()) {
                val quality = keys.next()
                val main = data.optJSONObject(quality)?.optJSONObject("main") ?: continue
                val hls = main.optString("hls", "").takeIf { it.startsWith("http") }
                val flv = main.optString("flv", "").takeIf { it.startsWith("http") }
                if (hls == null && flv == null) continue
                val isOrigin = quality.equals("origin", true) || quality.equals("origion", true)
                val (w, h, vbitrate) = parseSdkParams(main.optString("sdk_params", ""))
                // Origin (the raw source) always wins; otherwise rank by pixels then bitrate.
                val score = if (isOrigin) Long.MAX_VALUE else w.toLong() * h.toLong() * 1_000_000L + vbitrate
                val current = best
                if (current == null || score > current.score) best = Pick(score, hls, flv)
            }
            val pick = best ?: return null
            return when {
                pick.hls != null -> LiveStatus.Live(pick.hls, StreamType.HLS)
                pick.flv != null -> LiveStatus.Live(pick.flv, StreamType.PROGRESSIVE)
                else -> null
            }
        }

        private data class Pick(val score: Long, val hls: String?, val flv: String?)

        /** Parse a `sdk_params` blob -> (width, height, vbitrate). */
        private fun parseSdkParams(raw: String): Triple<Int, Int, Long> {
            if (raw.isBlank()) return Triple(0, 0, 0)
            return runCatching {
                val obj = JSONObject(raw)
                val parts = obj.optString("resolution", "").split("x")
                Triple(
                    parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0,
                    parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                    obj.optLong("vbitrate", 0L),
                )
            }.getOrDefault(Triple(0, 0, 0))
        }

        /** Find and parse the `stream_data` JSON (a double-encoded string) anywhere in the tree. */
        private fun findStreamData(node: Any?): JSONObject? {
            when (node) {
                is JSONObject -> {
                    (node.opt("stream_data") as? String)?.let { raw ->
                        if (raw.contains("\"main\"")) {
                            runCatching { JSONObject(raw) }.getOrNull()?.let { if (it.has("data")) return it }
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) findStreamData(node.opt(keys.next()))?.let { return it }
                }

                is JSONArray -> {
                    for (i in 0 until node.length()) findStreamData(node.opt(i))?.let { return it }
                }

                is String -> {
                    if (node.contains("\"main\"") && (node.contains("\"hls\"") || node.contains("\"flv\""))) {
                        val parsed = runCatching { JSONTokener(node).nextValue() }.getOrNull()
                        if (parsed is JSONObject) {
                            if (parsed.has("data")) return parsed
                            findStreamData(parsed)?.let { return it }
                        }
                    }
                }
            }
            return null
        }

        private class StreamAcc {
            var hls: String? = null
            var flv: String? = null
            val done get() = hls != null && flv != null
        }

        private fun walk(node: Any?, acc: StreamAcc) {
            if (acc.done) return
            when (node) {
                is JSONObject -> {
                    // webcast/room/info: single-string HLS at data.stream_url.hls_pull_url
                    if (acc.hls == null) node.optString("hls_pull_url", "").let { if (looksHls(it)) acc.hls = it }
                    // older/alternate shape: a quality map
                    if (acc.hls == null) {
                        node.optJSONObject("hls_pull_url_map")?.let { m -> pickBest(m)?.let { if (looksHls(it)) acc.hls = it } }
                    }
                    // FLV: usually a {HD1,SD1} map, occasionally a single string
                    if (acc.flv == null) {
                        val flvObj = node.optJSONObject("flv_pull_url")
                        if (flvObj != null) {
                            pickBest(flvObj)?.let { if (looksFlv(it)) acc.flv = it }
                        } else {
                            node.optString("flv_pull_url", "").let { if (looksFlv(it)) acc.flv = it }
                        }
                    }
                    // deepest fallback: per-quality blobs carry bare "hls"/"flv" URL fields
                    if (acc.hls == null) node.optString("hls", "").let { if (looksHls(it)) acc.hls = it }
                    if (acc.flv == null) node.optString("flv", "").let { if (looksFlv(it)) acc.flv = it }

                    val keys = node.keys()
                    while (keys.hasNext() && !acc.done) walk(node.opt(keys.next()), acc)
                }

                is JSONArray -> {
                    var i = 0
                    while (i < node.length() && !acc.done) {
                        walk(node.opt(i), acc)
                        i++
                    }
                }

                is String -> {
                    // Some fields (e.g. stream_data) are double-encoded JSON strings — descend in.
                    val s = node.trim()
                    if (s.length > 2 && s[0] == '{' &&
                        (s.contains("pull_url") || s.contains("\"hls\"") || s.contains("\"flv\""))
                    ) {
                        runCatching { walk(JSONTokener(s).nextValue(), acc) }
                    }
                }
            }
        }

        private fun pickBest(map: JSONObject): String? {
            if (map.length() == 0) return null
            val keys = map.keys().asSequence().toList()
            for (preferred in QUALITY_PRIORITY) {
                val match = keys.firstOrNull { it.equals(preferred, ignoreCase = true) } ?: continue
                map.optString(match, "").takeIf { it.isNotBlank() }?.let { return it }
            }
            return keys.firstNotNullOfOrNull { map.optString(it, "").takeIf { url -> url.isNotBlank() } }
        }

        private fun extractEmbeddedJson(html: String): String? {
            for (id in listOf("__UNIVERSAL_DATA_FOR_REHYDRATION__", "SIGI_STATE")) {
                val idx = html.indexOf("id=\"$id\"")
                if (idx < 0) continue
                val open = html.indexOf('>', idx)
                val close = html.indexOf("</script>", open)
                if (open < 0 || close < 0) continue
                val json = html.substring(open + 1, close).trim()
                if (json.startsWith("{")) return json
            }
            return null
        }

        private fun looksHls(url: String) = url.startsWith("http") && url.contains(".m3u8")
        private fun looksFlv(url: String) = url.startsWith("http") && url.contains(".flv")
    }
}

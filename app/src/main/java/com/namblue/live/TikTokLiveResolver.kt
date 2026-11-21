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
 *   4. Upgrade: if the ladder omits the raw source, try the unlisted one (see [upgradedToSource]).
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
    private val cookieProvider: () -> String? = { null },
    /** Whether this device can decode H.265 — source-quality streams are often H.265. */
    private val canDecodeHevc: () -> Boolean = { false },
    /** Receives the creator's profile from the lookup the resolver makes anyway (no extra request). */
    private val profileSink: (LiveProfile) -> Unit = {},
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * [allowSourceUpgrade] = false returns only the qualities TikTok lists, skipping the unlisted
     * source — the caller's way out when that source will not decode on this device.
     */
    suspend fun resolve(allowSourceUpgrade: Boolean = true): LiveStatus = withContext(Dispatchers.IO) {
        runCatching {
            val apiBody = httpGet(
                "$API_LIVE_BASE?aid=$AID&sourceType=54&uniqueId=$uniqueId",
                referer = "https://www.tiktok.com/@$uniqueId/live",
            )
            val roomId = apiBody?.let { parseRoomId(it) }
            val isLive = apiBody?.let { parseIsLive(it) } ?: false
            LiveProfiles.parse(apiBody)?.let(profileSink)

            if (roomId != null) {
                return@runCatching when (val viaApi = fetchStreamInfo(roomId, allowSourceUpgrade)) {
                    is LiveStatus.Live -> viaApi
                    is LiveStatus.Error -> viaApi          // network/HTTP issue -> reconnecting
                    // Live but no stream URL -> TikTok is gating it (age/sensitive) -> WebView login.
                    LiveStatus.Offline, LiveStatus.Restricted ->
                        if (isLive) LiveStatus.Restricted else LiveStatus.Offline
                }
            }
            // No room id at all (never went live / handle changed) -> last-resort HTML scrape.
            when (val viaHtml = resolveFromHtml(uniqueId, allowSourceUpgrade)) {
                is LiveStatus.Live -> viaHtml
                else -> if (isLive) LiveStatus.Restricted else LiveStatus.Offline
            }
        }.getOrElse { e ->
            LiveStatus.Error(e.message ?: "unknown")
        }
    }

    // --- Network steps (run inside resolve()'s IO context; OkHttp calls block) ---------------

    private fun fetchStreamInfo(roomId: String, allowSourceUpgrade: Boolean): LiveStatus {
        val url = "$WEBCAST_INFO_BASE?aid=$AID&room_id=$roomId"
        val body = httpGet(url, referer = "https://www.tiktok.com/") ?: return LiveStatus.Error("room/info HTTP error")
        return liveFrom(body, allowSourceUpgrade)
    }

    private fun resolveFromHtml(uniqueId: String, allowSourceUpgrade: Boolean): LiveStatus {
        val url = "https://www.tiktok.com/@$uniqueId/live"
        val html = httpGet(url, referer = "https://www.tiktok.com/") ?: return LiveStatus.Error("live page HTTP error")
        val json = extractEmbeddedJson(html) ?: return LiveStatus.Offline
        return liveFrom(json, allowSourceUpgrade)
    }

    private fun liveFrom(body: String, allowSourceUpgrade: Boolean): LiveStatus {
        val listed = extractLive(body)
        if (listed !is LiveStatus.Live || !allowSourceUpgrade) return listed
        return upgradedToSource(body) ?: listed
    }

    /**
     * TikTok often lists only transcodes (e.g. 720p) and leaves the raw source out of the ladder,
     * yet the audio-only entry is that same source with `only_audio=1` appended. Dropping the flag
     * yields the full-quality stream (measured: 1080p 6 Mbps where only 720p 1.8 Mbps was listed).
     * This is undocumented, so the URL is used only after the CDN proves it serves video there in
     * a codec this device can play (the source is often H.265 at 60 fps, unlike the transcodes).
     */
    private fun upgradedToSource(body: String): LiveStatus.Live? {
        val candidate = extractSourceCandidate(body) ?: return null
        val playable = when (probeVideoCodec(candidate)) {
            FLV_CODEC_AVC -> true
            FLV_CODEC_HEVC -> canDecodeHevc()
            else -> false
        }
        return if (playable) LiveStatus.Live(candidate, StreamType.PROGRESSIVE) else null
    }

    /** Read the first bytes of the FLV at [url] to learn its video codec; null on any failure. */
    private fun probeVideoCodec(url: String): Int? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        probeClient.newCall(request).execute().use { response ->
            val source = response.body?.source()
            if (!response.isSuccessful || source == null) return@use null
            source.request(PROBE_BYTES) // false just means the stream ended early; use what came
            flvVideoCodec(source.buffer.readByteArray(minOf(source.buffer.size, PROBE_BYTES)))
        }
    }.getOrNull()

    // Short timeouts: a dead candidate must not noticeably delay falling back to the listed quality.
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Returns the body on 2xx, null on other status codes; rethrows real network failures. */
    private fun httpGet(url: String, referer: String): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
        // Send the logged-in session cookie (from the WebView login) so gated/age-restricted
        // lives resolve natively. Harmless when logged out (cookie is null).
        cookieProvider()?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
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

        private const val PROBE_TIMEOUT_SECONDS = 5L
        // The first video tag sits ~1 KB in (after the metadata and audio config tags).
        private const val PROBE_BYTES = 16L * 1024
        private const val FLV_HEADER_SIZE = 9
        private const val FLV_PREVIOUS_TAG_SIZE = 4
        private const val FLV_TAG_HEADER_SIZE = 11
        private const val FLV_TAG_TYPE_MASK = 0x1F
        private const val FLV_TAG_VIDEO = 9
        private const val FLV_CODEC_MASK = 0x0F
        private const val FLV_CODEC_AVC = 7
        private const val FLV_CODEC_HEVC = 12 // de-facto id, not in the FLV spec
        private val AUDIO_ONLY_PARAM = Regex("([?&])only_audio=1(&|$)")

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

        /** True when api-live says the user is live now (status == 2). Pure; unit-tested. */
        fun parseIsLive(jsonText: String): Boolean = runCatching {
            val data = JSONObject(jsonText).optJSONObject("data") ?: return false
            val liveStatus = data.optJSONObject("liveRoom")?.optInt("status", -1) ?: -1
            val userStatus = data.optJSONObject("user")?.optInt("status", -1) ?: -1
            liveStatus == 2 || userStatus == 2
        }.getOrDefault(false)

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
         * The unlisted raw-source URL hiding behind the audio-only entry, or null when the source
         * is already listed (nothing to gain) or there is no audio-only entry. Pure; unit-tested.
         */
        fun extractSourceCandidate(jsonText: String): String? = runCatching {
            val data = findStreamData(JSONTokener(jsonText).nextValue())?.optJSONObject("data") ?: return null
            val qualities = data.keys().asSequence().toList()
            if (qualities.any(::isOriginKey)) return null
            qualities.firstNotNullOfOrNull { quality ->
                stripAudioOnly(data.optJSONObject(quality)?.optJSONObject("main")?.optString("flv", "").orEmpty())
            }
        }.getOrNull()

        /** [url] minus its `only_audio=1` flag; null unless it is an audio-only FLV url. Pure. */
        fun stripAudioOnly(url: String): String? {
            if (!looksFlv(url) || !AUDIO_ONLY_PARAM.containsMatchIn(url)) return null
            // Keep the separator when another parameter follows, drop it when the flag was last.
            return AUDIO_ONLY_PARAM.replace(url) { m -> if (m.groupValues[2].isEmpty()) "" else m.groupValues[1] }
        }

        /**
         * FLV codec id (7 = H.264, 12 = H.265) of the first video tag in [prefix], the leading
         * bytes of a stream. Null when it is not FLV or no video tag starts within [prefix] — which
         * is what an audio-only stream looks like. Pure; unit-tested.
         */
        fun flvVideoCodec(prefix: ByteArray): Int? {
            val isFlv = prefix.size >= FLV_HEADER_SIZE &&
                prefix[0] == 'F'.code.toByte() && prefix[1] == 'L'.code.toByte() && prefix[2] == 'V'.code.toByte()
            if (!isFlv) return null
            var tagStart = FLV_HEADER_SIZE + FLV_PREVIOUS_TAG_SIZE
            while (tagStart + FLV_TAG_HEADER_SIZE < prefix.size) {
                val bodyStart = tagStart + FLV_TAG_HEADER_SIZE
                if (prefix[tagStart].toInt() and FLV_TAG_TYPE_MASK == FLV_TAG_VIDEO) {
                    return prefix[bodyStart].toInt() and FLV_CODEC_MASK
                }
                val bodySize = (1..3).fold(0) { size, i -> (size shl 8) or (prefix[tagStart + i].toInt() and 0xFF) }
                tagStart = bodyStart + bodySize + FLV_PREVIOUS_TAG_SIZE
            }
            return null
        }

        private fun isOriginKey(quality: String) =
            quality.equals("origin", true) || quality.equals("origion", true)

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
                val isOrigin = isOriginKey(quality)
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

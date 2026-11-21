package com.namblue.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Profiles through TikTok's official creator embed (tiktok.com/embed/@user), the widget sites
 * use to show a creator's latest posts. Its HTML ships the profile and the ~10 latest posts as
 * JSON, so one plain request is enough: no WebView, no page scripts, no bot check.
 *
 * Creators with audience controls (age limits) don't allow embedding and come back
 * [Result.Unavailable]; their full page is still reachable through [ProfileDirectory].
 */
object ProfileEmbed {

    sealed interface Result {
        data class Ok(val header: ProfileHeader, val posts: List<ProfilePost>) : Result

        /** TikTok refuses to embed this creator (audience controls, private, or no such user). */
        data object Unavailable : Result

        /** The page came back without its data (network trouble or a changed page). */
        data object Unreadable : Result
    }

    private const val EMBED = "https://www.tiktok.com/embed/@"
    private const val TIMEOUT_SECONDS = 15L
    private const val UINT_RANGE = 1L shl 32
    private val STATE_SCRIPT =
        Regex("""<script id="__FRONTITY_CONNECT_STATE__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** Recent successful answers, so opening a profile right after finding it costs no request. */
    private val cache = ConcurrentHashMap<String, Pair<Long, Result.Ok>>()
    private const val CACHE_MS = 5 * 60_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun load(username: String): Result = withContext(Dispatchers.IO) {
        val key = username.lowercase()
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return@withContext it.second }
        val html = runCatching {
            val request = Request.Builder()
                .url(EMBED + URLEncoder.encode(username, "UTF-8"))
                .header("User-Agent", TikTokLiveResolver.USER_AGENT)
                .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
                .build()
            // Refused creators come back as HTTP 400 *with* the page state explaining why, so the
            // body is read whatever the status; parse() decides what it means.
            client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull()
        parse(html, username).also { if (it is Result.Ok) cache[key] = System.currentTimeMillis() to it }
    }

    /** Read the embed page's state for [username]. Pure; unit-tested. */
    fun parse(html: String?, username: String): Result {
        val state = html?.let { STATE_SCRIPT.find(it)?.groupValues?.get(1) } ?: return Result.Unreadable
        val data = runCatching { JSONObject(state).getJSONObject("source").getJSONObject("data") }.getOrNull()
            ?: return Result.Unreadable
        val key = data.keys().asSequence().firstOrNull { it.equals("/embed/@$username", ignoreCase = true) }
            ?: return Result.Unreadable
        val page = data.optJSONObject(key) ?: return Result.Unreadable
        if (page.optBoolean("isError", false)) return Result.Unavailable
        val user = page.optJSONObject("userInfo") ?: return Result.Unreadable
        return Result.Ok(header(user), posts(page, username))
    }

    private fun header(user: JSONObject) = ProfileHeader(
        nickname = user.optString("nickname", "").trim().ifEmpty { null },
        followers = count(user, "followerCount"),
        following = count(user, "followingCount"),
        likes = count(user, "heartCount"),
        bio = user.optString("signature", "").trim().ifEmpty { null },
        avatarUrl = user.optString("avatarThumbUrl", "").takeIf { it.startsWith("https://") },
        needsLogin = false,
        needsVerification = false,
        followerCount = user.takeIf { it.has("followerCount") }?.optLong("followerCount")?.let(::unsignedCount),
    )

    private fun posts(page: JSONObject, username: String): List<ProfilePost> {
        val list = page.optJSONArray("videoList") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id", "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapNotNull null
            if (item.optBoolean("privateItem", false)) return@mapNotNull null
            // Photo posts have no video stream and no frame size.
            val isPhoto = item.optString("playAddr", "").isBlank() && item.optInt("width", 0) == 0
            ProfilePost(
                id = id,
                username = item.optString("authorUniqueId", "").ifEmpty { username },
                kind = if (isPhoto) PostKind.PHOTO else PostKind.VIDEO,
                thumbUrl = item.optString("coverUrl", "").takeIf { it.startsWith("https://") },
                caption = item.optString("desc", "").trim().ifEmpty { null },
                views = item.takeIf { it.has("playCount") }?.optLong("playCount")?.let(LiveProfiles::formatViewers),
            )
        }
    }

    private fun count(obj: JSONObject, key: String): String? =
        obj.takeIf { it.has(key) }?.optLong(key)?.let { LiveProfiles.formatViewers(unsignedCount(it)) }

    /** TikTok sends some big counts (e.g. total likes) as an overflowed signed 32-bit int. Pure. */
    fun unsignedCount(value: Long): Long = if (value < 0) value + UINT_RANGE else value
}

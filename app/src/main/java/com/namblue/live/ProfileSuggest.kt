package com.namblue.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Approximate account search without a TikTok login.
 *
 * TikTok's user search only answers logged-in visitors, but its search-box suggestions
 * (api/search/general/sug) are open. They are keywords, not accounts — yet many are, or turn
 * into, real usernames ("khánh duy 71" → "khanhduy71"). [candidates] makes those guesses;
 * the caller confirms each one through [ProfileEmbed], which also supplies photo and name.
 */
object ProfileSuggest {

    /** Upper bound of guesses checked per search, to keep a search to a handful of requests. */
    const val MAX_CANDIDATES = 8

    private const val API = "https://www.tiktok.com/api/search/general/sug/?aid=1988&from_page=search&keyword="
    private const val MIN_HANDLE = 2
    private const val MAX_HANDLE = 24
    private const val TIMEOUT_SECONDS = 10L
    private val MARKS = Regex("""\p{M}+""")
    private val NOT_HANDLE = Regex("""[^a-z0-9._]""")
    private val WHITESPACE = Regex("""\s+""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** TikTok's suggestions for [query]; empty on any failure. */
    suspend fun load(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(suggestUrl(query))
                .header("User-Agent", TikTokLiveResolver.USER_AGENT)
                .header("Referer", "https://www.tiktok.com/")
                .build()
            client.newCall(request).execute().use { if (it.isSuccessful) parse(it.body?.string()) else emptyList() }
        }.getOrDefault(emptyList())
    }

    fun suggestUrl(query: String): String = API + URLEncoder.encode(query.trim(), "UTF-8")

    /** Suggestion texts in TikTok's order. Pure; unit-tested. */
    fun parse(body: String?): List<String> = runCatching {
        val list = JSONObject(body.orEmpty()).optJSONArray("sug_list") ?: return emptyList()
        (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("content", "")?.trim()?.ifEmpty { null } }
    }.getOrDefault(emptyList())

    /** "Đặng Văn Lâm" → "dangvanlam": what the text would look like as a TikTok username. Pure. */
    fun toHandle(text: String): String {
        val lower = text.lowercase().replace('đ', 'd')
        val bare = MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        return NOT_HANDLE.replace(bare, "").trim('.')
    }

    /**
     * Usernames worth checking for [query]: the typed text first — several words also joined by
     * "." and "_", the usual ways handles separate words — then the suggestions. Pure.
     */
    fun candidates(query: String, suggestions: List<String>): List<String> =
        (typedVariants(query) + suggestions.map(::toHandle))
            .filter { it.length in MIN_HANDLE..MAX_HANDLE }
            .distinct()
            .take(MAX_CANDIDATES)

    private fun typedVariants(query: String): List<String> {
        val words = query.trim().split(WHITESPACE).map(::toHandle).filter { it.isNotEmpty() }
        if (words.size < 2) return listOf(toHandle(query))
        return listOf("", ".", "_").map { words.joinToString(it) }
    }
}

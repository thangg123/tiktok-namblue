package com.namblue.live

import org.json.JSONArray
import java.net.URLEncoder
import org.json.JSONObject
import org.json.JSONTokener

/** One creator who is live right now, as listed on tiktok.com/live. */
data class LiveChannel(
    val username: String,
    val nickname: String,
    /** Room title, when the listing shows one (feed cards do, the sidebar does not). */
    val title: String?,
    /** Viewer count exactly as TikTok displays it ("179", "1,2K"), or null if not shown. */
    val viewers: String?,
    val avatarUrl: String?,
)

/** A section of TikTok LIVE (Game, Phong cách sống, Garena Free Fire…) and the page that lists it. */
data class LiveCategory(val label: String, val url: String)

/**
 * Reads the list of live channels off tiktok.com/live.
 *
 * TikTok's live feed API only answers requests signed by its own page scripts, so instead of
 * calling it directly the app loads the page in a WebView and runs [SCRAPE_JS], which collects
 * every `/@user/live` link the page has rendered. [parse] turns that raw dump into channels.
 */
object LiveDirectory {

    private const val ORIGIN = "https://www.tiktok.com"
    const val PAGE_URL = "$ORIGIN/live"
    private const val SEARCH_URL = "$ORIGIN/search/live?q="

    /**
     * Collects each rendered link to a live page or live section: its href, its leaf texts
     * (username, nickname, viewer count or room title, depending on the card), its first image
     * and how far from the left edge it sits (the sidebar hugs the left edge).
     * Returns a JSON string; all interpretation happens in [parse] so it can be unit-tested.
     */
    const val SCRAPE_JS = """
        (function () {
          var out = [];
          document.querySelectorAll('a[href*="/live"]').forEach(function (a) {
            var texts = [];
            a.querySelectorAll('*').forEach(function (el) {
              if (el.children.length === 0) {
                var t = (el.textContent || '').trim();
                if (t && t !== 'LIVE') texts.push(t);
              }
            });
            // Tabs keep their label directly in the link, with no child elements.
            if (!texts.length) {
              var own = (a.textContent || '').trim();
              if (own) texts.push(own);
            }
            var img = a.querySelector('img');
            var left = a.getBoundingClientRect().left / Math.max(window.innerWidth, 1);
            out.push({ h: a.getAttribute('href') || '', t: texts, a: img ? img.src : '', x: left });
          });
          return JSON.stringify(out);
        })()
    """

    private val LIVE_HREF =
        Regex("""^(?:https://www\.tiktok\.com)?/@([A-Za-z0-9._]{1,40})/live/?(?:[?#].*)?$""")
    private val SECTION_HREF =
        Regex("""^(?:https://www\.tiktok\.com)?(/live/[A-Za-z0-9_.:%-]+(?:/[A-Za-z0-9_.:%-]+)?)/?$""")
    private val LOGIN_ONLY_SECTIONS = setOf("/live/following")

    /** "/live/category/…" mirrors the real sections (and "/live/category" is a browse index). */
    private const val CATEGORY_INDEX = "/live/category"
    private val SESSION_COOKIE = Regex("""(?:^|;\s*)sessionid=([^;\s]+)""")

    /** Links left of this fraction of the page width belong to the generic sidebar. */
    private const val SIDEBAR_EDGE = 0.2

    private val VIEWER_COUNT = Regex("""^\d[\d.,]*\s*[KMBNkmbn]?$""")
    private const val LIVE_BADGE = "LIVE"

    /**
     * Parse the output of [SCRAPE_JS] — either the JSON array itself or the quoted string that
     * `WebView.evaluateJavascript` hands back. Unknown links are skipped, duplicates keep their
     * first appearance (TikTok's own order), and anything unreadable yields an empty list.
     *
     * [includeSidebar] = false drops the "recommended creators" sidebar, which is the same on
     * every page — needed so a category or search shows only its own rooms.
     */
    fun parse(raw: String?, includeSidebar: Boolean = true): List<LiveChannel> {
        val seen = mutableSetOf<String>()
        return objects(raw)
            .filter { includeSidebar || !isInSidebar(it) }
            .mapNotNull(::toChannel)
            .filter { seen.add(it.username.lowercase()) }
    }

    /** The live sections the page links to (tabs and category tiles), in page order. */
    fun parseCategories(raw: String?): List<LiveCategory> {
        val seenPaths = mutableSetOf<String>()
        val seenLabels = mutableSetOf<String>()
        return objects(raw).mapNotNull { entry ->
            val href = entry.opt("h") as? String ?: return@mapNotNull null
            val path = SECTION_HREF.find(href.trim())?.groupValues?.get(1) ?: return@mapNotNull null
            val label = textsOf(entry).firstOrNull() ?: return@mapNotNull null
            val skip = path in LOGIN_ONLY_SECTIONS || path.startsWith(CATEGORY_INDEX)
            if (skip || !seenPaths.add(path) || !seenLabels.add(label.lowercase())) return@mapNotNull null
            LiveCategory(label, ORIGIN + path)
        }
    }

    /** TikTok's live search page for [query], or null when there is nothing to search. */
    fun searchUrl(query: String): String? =
        query.trim().takeIf { it.isNotEmpty() }?.let { SEARCH_URL + URLEncoder.encode(it, "UTF-8") }

    /** True when the tiktok.com cookie header carries a signed-in session (search needs one). */
    fun isLoggedIn(cookieHeader: String?): Boolean =
        cookieHeader?.let { SESSION_COOKIE.containsMatchIn(it) } ?: false

    private fun isInSidebar(entry: JSONObject): Boolean {
        val left = entry.opt("x") as? Number ?: return false
        return left.toDouble() < SIDEBAR_EDGE
    }

    private fun objects(raw: String?): List<JSONObject> {
        val array = toArray(raw) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun toArray(raw: String?): JSONArray? {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return null
        val value = runCatching { JSONTokener(text).nextValue() }.getOrNull()
        return when {
            value is JSONArray -> value
            // evaluateJavascript wraps the result in a JSON string literal; unwrap exactly once.
            value is String && text.startsWith('"') -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        }
    }

    private fun toChannel(entry: JSONObject): LiveChannel? {
        val href = entry.opt("h") as? String ?: return null
        val username = LIVE_HREF.find(href.trim())?.groupValues?.get(1) ?: return null
        val texts = textsOf(entry).filterNot { it.equals(username, ignoreCase = true) || it == LIVE_BADGE }
        val viewers = texts.lastOrNull { VIEWER_COUNT.matches(it) }
        // A lone character is the letter TikTok draws in place of a missing avatar.
        val labels = texts.filterNot { it == viewers || it.codePointCount(0, it.length) <= 1 }
        return LiveChannel(
            username = username,
            nickname = labels.lastOrNull() ?: username,
            title = labels.firstOrNull()?.takeIf { labels.size > 1 },
            viewers = viewers,
            avatarUrl = (entry.opt("a") as? String)?.takeIf { it.startsWith("https://") },
        )
    }

    private fun textsOf(entry: JSONObject): List<String> {
        val array = entry.optJSONArray("t") ?: return emptyList()
        return (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }
    }
}

package com.namblue.live

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder

enum class PostKind { VIDEO, PHOTO }

/** One post in a creator's grid. */
data class ProfilePost(
    val id: String,
    val username: String,
    val kind: PostKind,
    val thumbUrl: String?,
    val caption: String?,
    /** View count as TikTok displays it ("1.2M"), or null. */
    val views: String?,
    /** A photo album's pictures, in order — known only from the logged-in post list. */
    val images: List<String> = emptyList(),
    /** A photo album's background music. */
    val musicUrl: String? = null,
)

/**
 * The top of a profile page. [needsLogin] = TikTok hides this profile from logged-out viewers;
 * [needsVerification] = TikTok put a captcha in front of the posts, which only a person may solve.
 */
data class ProfileHeader(
    val nickname: String?,
    val followers: String?,
    val following: String?,
    val likes: String?,
    val bio: String?,
    val avatarUrl: String?,
    val needsLogin: Boolean,
    val needsVerification: Boolean,
    /** Exact follower count when known (the embed gives it), for ranking search results. */
    val followerCount: Long? = null,
)

/** One account in user search results. */
data class ProfileHit(val username: String, val nickname: String, val avatarUrl: String?, val followers: String?)

/**
 * Reads TikTok profile pages (tiktok.com/@user) and user search results, the same way
 * [LiveDirectory] reads the live feed: the page is loaded in a WebView, [SCRAPE_JS] dumps its
 * links and header, and the functions here interpret that dump. Posts play in TikTok's official
 * embed player ([playerUrl]).
 */
object ProfileDirectory {

    private const val ORIGIN = "https://www.tiktok.com"

    /**
     * Dumps every link to a profile or post (href, leaf texts, first image and its alt text,
     * horizontal position) plus the profile header, read from TikTok's data-e2e attributes.
     */
    const val SCRAPE_JS = """
        (function () {
          function text(sel) {
            var el = document.querySelector('[data-e2e="' + sel + '"]');
            return el ? (el.textContent || '').trim() : '';
          }
          var links = [];
          document.querySelectorAll('a[href*="/@"]').forEach(function (a) {
            var texts = [];
            a.querySelectorAll('*').forEach(function (el) {
              if (el.children.length === 0) {
                var t = (el.textContent || '').trim();
                if (t) texts.push(t);
              }
            });
            var img = a.querySelector('img');
            // Lazy tiles may keep the real address in srcset or data-src until scrolled into view.
            var src = img ? (img.currentSrc || img.getAttribute('src') || img.getAttribute('data-src') ||
              ((img.getAttribute('srcset') || '').split(' ')[0])) : '';
            var left = a.getBoundingClientRect().left / Math.max(window.innerWidth, 1);
            links.push({ h: a.getAttribute('href') || '', t: texts,
                         a: src || '', alt: img ? (img.alt || '') : '', x: left });
          });
          var avatar = document.querySelector('[data-e2e="user-avatar"] img');
          var title = text('user-title');
          var loginWall = !title && Array.prototype.some.call(document.querySelectorAll('button'),
            function (b) { return /log in|đăng nhập/i.test(b.textContent || ''); });
          var captcha = !!document.querySelector('[id*="captcha"], [class*="captcha"]') ||
            /drag the slider|kéo thanh trượt|fit the puzzle/i.test(document.body.innerText || '');
          var header = document.querySelector('[data-e2e="user-page"]') || loginWall || captcha ? {
            title: title, followers: text('followers-count'), following: text('following-count'),
            likes: text('likes-count'), bio: text('user-bio'), avatar: avatar ? avatar.src : '',
            login: loginWall, captcha: captcha
          } : null;
          return JSON.stringify({ links: links, header: header });
        })()
    """

    private val POST_HREF =
        Regex("""^(?:https://www\.tiktok\.com)?/@([A-Za-z0-9._]{1,40})/(video|photo)/(\d{5,25})(?:[/?#].*)?$""")
    private val PROFILE_HREF =
        Regex("""^(?:https://www\.tiktok\.com)?/@([A-Za-z0-9._]{1,40})/?(?:[?#].*)?$""")
    private val USERNAME = Regex("""^[A-Za-z0-9._]{1,40}$""")
    private val COUNT = Regex("""^\d[\d.,]*\s*[KMBNkmbn]?$""")

    /** Links left of this fraction of the page width belong to the navigation sidebar. */
    private const val SIDEBAR_EDGE = 0.2

    fun profileUrl(username: String): String = "$ORIGIN/@$username"

    fun userSearchUrl(query: String): String? =
        query.trim().takeIf { it.isNotEmpty() }?.let { "$ORIGIN/search/user?q=" + URLEncoder.encode(it, "UTF-8") }

    /** TikTok's official embed player, set up for a TV: autoplay, caption shown, no related videos. */
    fun playerUrl(postId: String): String =
        "$ORIGIN/player/v1/$postId?autoplay=1&loop=0&controls=1&progress_bar=1&play_button=1" +
            "&volume_control=1&fullscreen_button=0&timestamp=1&music_info=1&description=1&rel=0&native_context_menu=0"

    fun isUsername(text: String): Boolean = USERNAME.matches(text)

    /** The profile's posts in grid order, without duplicates. Pure; unit-tested. */
    fun parsePosts(raw: String?): List<ProfilePost> {
        val seen = mutableSetOf<String>()
        return links(raw).mapNotNull { link ->
            val match = POST_HREF.find(stringOf(link, "h").trim()) ?: return@mapNotNull null
            val (username, kind, id) = match.destructured
            if (!seen.add(id)) return@mapNotNull null
            ProfilePost(
                id = id,
                username = username,
                kind = if (kind == "photo") PostKind.PHOTO else PostKind.VIDEO,
                thumbUrl = httpsOrNull(stringOf(link, "a")),
                caption = stringOf(link, "alt").trim().ifEmpty { null },
                views = textsOf(link).lastOrNull { COUNT.matches(it) },
            )
        }
    }

    /** The profile header, or null if the page has not rendered one yet. Pure; unit-tested. */
    fun parseHeader(raw: String?): ProfileHeader? {
        val header = root(raw)?.optJSONObject("header") ?: return null
        fun field(name: String) = (header.opt(name) as? String)?.trim()?.ifEmpty { null }
        return ProfileHeader(
            nickname = field("title"),
            followers = field("followers"),
            following = field("following"),
            likes = field("likes"),
            bio = field("bio"),
            avatarUrl = httpsOrNull(field("avatar").orEmpty()),
            needsLogin = header.optBoolean("login", false),
            needsVerification = header.optBoolean("captcha", false),
        )
    }

    /** Accounts listed by the user search page, in TikTok's order. Pure; unit-tested. */
    fun parseUsers(raw: String?): List<ProfileHit> {
        val seen = mutableSetOf<String>()
        return links(raw).mapNotNull { link ->
            val inSidebar = (link.opt("x") as? Number)?.toDouble()?.let { it < SIDEBAR_EDGE } ?: false
            if (inSidebar) return@mapNotNull null
            val username = PROFILE_HREF.find(stringOf(link, "h").trim())?.groupValues?.get(1) ?: return@mapNotNull null
            if (!seen.add(username.lowercase())) return@mapNotNull null
            val texts = textsOf(link).filterNot { it.equals(username, ignoreCase = true) }
            val followers = texts.lastOrNull { COUNT.matches(it) }
            ProfileHit(
                username = username,
                nickname = texts.firstOrNull { it != followers } ?: username,
                avatarUrl = httpsOrNull(stringOf(link, "a")),
                followers = followers,
            )
        }
    }

    private fun root(raw: String?): JSONObject? {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return null
        val value = runCatching { JSONTokener(text).nextValue() }.getOrNull()
        return when {
            value is JSONObject -> value
            // evaluateJavascript wraps the result in a JSON string literal; unwrap exactly once.
            value is String && text.startsWith('"') -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        }
    }

    private fun links(raw: String?): List<JSONObject> {
        val array: JSONArray = root(raw)?.optJSONArray("links") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun stringOf(obj: JSONObject, key: String): String = obj.opt(key) as? String ?: ""

    private fun textsOf(obj: JSONObject): List<String> {
        val array = obj.optJSONArray("t") ?: return emptyList()
        return (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun httpsOrNull(url: String): String? = url.takeIf { it.startsWith("https://") }
}

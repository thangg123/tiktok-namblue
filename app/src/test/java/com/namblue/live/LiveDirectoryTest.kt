package com.namblue.live

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing tests for [LiveDirectory]. Raw entries mirror what the scrape script reads off
 * tiktok.com/live: the sidebar ("Nhà sáng tạo LIVE đề xuất") and the feed cards.
 */
class LiveDirectoryTest {

    private fun entry(href: String, texts: List<String>, avatar: String = "", left: Double? = null): JSONObject =
        JSONObject().put("h", href).put("t", JSONArray(texts)).put("a", avatar).apply { left?.let { put("x", it) } }

    private fun raw(vararg entries: JSONObject): String = JSONArray(entries.toList()).toString()

    @Test
    fun `sidebar entry yields username, nickname, viewers and avatar`() {
        val json = raw(entry("/@nobita2hehe/live", listOf("nobita2hehe", "Nôbita 2", "179"), "https://p16/a.jpeg"))

        val channels = LiveDirectory.parse(json)

        assertEquals(listOf(LiveChannel("nobita2hehe", "Nôbita 2", null, "179", "https://p16/a.jpeg")), channels)
    }

    @Test
    fun `feed card entry keeps the room title and the nickname`() {
        val json = raw(entry("/@lazyboylive/live", listOf("TOP 1 Flo Live !!!", "LazyBoy Live")))

        val channel = LiveDirectory.parse(json).single()

        assertEquals("lazyboylive", channel.username)
        assertEquals("LazyBoy Live", channel.nickname)
        assertEquals("TOP 1 Flo Live !!!", channel.title)
        assertNull(channel.viewers)
        assertNull(channel.avatarUrl)
    }

    @Test
    fun `a lone letter is the avatar placeholder, not a nickname`() {
        val json = raw(
            entry("/@typicalgamer/live", listOf("D", "typicalgamer")),
            entry("/@chill/live", listOf("Chilled Vibez", "D", "J")),
        )

        val channels = LiveDirectory.parse(json)

        assertEquals("typicalgamer", channels[0].nickname)
        assertEquals("Chilled Vibez", channels[1].nickname)
    }

    @Test
    fun `abbreviated viewer counts are recognised`() {
        val json = raw(
            entry("/@a/live", listOf("a", "Anh A", "1,2K")),
            entry("/@b/live", listOf("b", "Chị B", "3.4M")),
            entry("/@c/live", listOf("c", "Em C", "12 N")),
        )

        assertEquals(listOf("1,2K", "3.4M", "12 N"), LiveDirectory.parse(json).map { it.viewers })
    }

    @Test
    fun `nickname falls back to the username when the entry has no text`() {
        val channel = LiveDirectory.parse(raw(entry("/@quiet_one/live", emptyList()))).single()

        assertEquals("quiet_one", channel.nickname)
    }

    @Test
    fun `duplicate usernames keep the first and richest entry order`() {
        val json = raw(
            entry("/@dup/live", listOf("dup", "Dup", "10")),
            entry("/@other/live", listOf("other", "Other", "5")),
            entry("https://www.tiktok.com/@dup/live?enter_from=feed", listOf("Room title", "Dup")),
        )

        assertEquals(listOf("dup", "other"), LiveDirectory.parse(json).map { it.username })
        assertEquals("10", LiveDirectory.parse(json).first().viewers)
    }

    @Test
    fun `links that are not a creator live page are ignored`() {
        val json = raw(
            entry("/live", listOf("Khám phá phiên LIVE")),
            entry("/live/category/gta", listOf("Grand Theft Auto VI")),
            entry("/@someone", listOf("profile, not live")),
            entry("/@bad name/live", listOf("space in handle")),
            entry("javascript:alert(1)", listOf("x")),
            entry("/@ok.user_1/live", listOf("ok.user_1", "OK", "7")),
        )

        assertEquals(listOf("ok.user_1"), LiveDirectory.parse(json).map { it.username })
    }

    @Test
    fun `avatar must be an https url`() {
        val json = raw(
            entry("/@a/live", listOf("a", "A"), "data:image/png;base64,xx"),
            entry("/@b/live", listOf("b", "B"), "http://insecure/b.jpg"),
        )

        assertTrue(LiveDirectory.parse(json).all { it.avatarUrl == null })
    }

    @Test
    fun `accepts the quoted string that evaluateJavascript returns`() {
        val inner = raw(entry("/@x/live", listOf("x", "X", "1")))
        val quoted = JSONObject.quote(inner)

        assertEquals(listOf("x"), LiveDirectory.parse(quoted).map { it.username })
    }

    @Test
    fun `garbage, null and empty input yield an empty list`() {
        assertEquals(emptyList<LiveChannel>(), LiveDirectory.parse(null))
        assertEquals(emptyList<LiveChannel>(), LiveDirectory.parse("null"))
        assertEquals(emptyList<LiveChannel>(), LiveDirectory.parse(""))
        assertEquals(emptyList<LiveChannel>(), LiveDirectory.parse("<html>captcha</html>"))
        assertEquals(emptyList<LiveChannel>(), LiveDirectory.parse("""[{"h":42}]"""))
    }

    // --- main content vs. the generic "recommended creators" sidebar -------------------------

    @Test
    fun `sidebar entries can be left out so a category shows only its own rooms`() {
        val json = raw(
            entry("/@side/live", listOf("side", "Side", "10"), left = 0.03),
            entry("/@main/live", listOf("Room", "Main"), left = 0.42),
            entry("/@unknown/live", listOf("unknown", "Unknown")),
        )

        assertEquals(listOf("side", "main", "unknown"), LiveDirectory.parse(json).map { it.username })
        assertEquals(listOf("main", "unknown"), LiveDirectory.parse(json, includeSidebar = false).map { it.username })
    }

    // --- categories ---------------------------------------------------------------------------

    @Test
    fun `category tabs are read from live section links in page order without duplicates`() {
        val json = raw(
            entry("/live", listOf("Đề xuất")),
            entry("/live/following", listOf("Đang Follow")),
            entry("/live/gaming", listOf("Game")),
            entry("/live/lifestyle", listOf("Phong cách sống")),
            entry("/live/gaming/Garena_Free_Fire", listOf("Garena Free Fire")),
            entry("/live/gaming/Garena_Free_Fire", listOf("Garena Free Fire", "85.7k người đang xem")),
            entry("/@someone/live", listOf("someone")),
            entry("/live/gaming/Bad Slug", listOf("space")),
            entry("/live/gaming/NoLabel", emptyList()),
        )

        assertEquals(
            listOf(
                LiveCategory("Game", "https://www.tiktok.com/live/gaming"),
                LiveCategory("Phong cách sống", "https://www.tiktok.com/live/lifestyle"),
                LiveCategory("Garena Free Fire", "https://www.tiktok.com/live/gaming/Garena_Free_Fire"),
            ),
            LiveDirectory.parseCategories(json),
        )
    }

    @Test
    fun `category slugs may contain a colon and duplicate category listings are skipped`() {
        val json = raw(
            entry("/live/gaming/PUBG:_BATTLEGROUNDS", listOf("PUBG: BATTLEGROUNDS")),
            entry("/live/category/gaming", listOf("Gaming")),
            entry("/live/category", listOf("Browse all")),
            entry("/live/gaming", listOf("Gaming")),
            entry("/live/gaming/Gaming", listOf("Gaming")),
        )

        assertEquals(
            listOf(
                LiveCategory("PUBG: BATTLEGROUNDS", "https://www.tiktok.com/live/gaming/PUBG:_BATTLEGROUNDS"),
                LiveCategory("Gaming", "https://www.tiktok.com/live/gaming"),
            ),
            LiveDirectory.parseCategories(json),
        )
    }

    @Test
    fun `categories from garbage input are empty`() {
        assertEquals(emptyList<LiveCategory>(), LiveDirectory.parseCategories("<html/>"))
        assertEquals(emptyList<LiveCategory>(), LiveDirectory.parseCategories(null))
    }

    // --- search & login -------------------------------------------------------------------------

    @Test
    fun `search url encodes the query`() {
        assertEquals(
            "https://www.tiktok.com/search/live?q=li%C3%AAn+qu%C3%A2n+%26+co",
            LiveDirectory.searchUrl("  liên quân & co "),
        )
    }

    @Test
    fun `blank search has no url`() {
        assertNull(LiveDirectory.searchUrl("   "))
    }

    @Test
    fun `logged in only when the session cookie has a value`() {
        assertTrue(LiveDirectory.isLoggedIn("ttwid=1; sessionid=abc123; tt_csrf_token=x"))
        assertTrue(LiveDirectory.isLoggedIn("sessionid=abc"))
        assertFalse(LiveDirectory.isLoggedIn("ttwid=1; sessionid=; other=2"))
        assertFalse(LiveDirectory.isLoggedIn("ttwid=1; sessionid_ss_fake=zz"))
        assertFalse(LiveDirectory.isLoggedIn(null))
    }
}

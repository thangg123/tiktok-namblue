package com.namblue.live

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing tests for [ProfileDirectory]. Raw dumps mirror what [ProfileDirectory.SCRAPE_JS]
 * reads off tiktok.com/@user (post grid + data-e2e header) and the user search page.
 */
class ProfileDirectoryTest {

    private fun link(href: String, texts: List<String> = emptyList(), img: String = "", alt: String = "", left: Double = 0.5) =
        JSONObject().put("h", href).put("t", JSONArray(texts)).put("a", img).put("alt", alt).put("x", left)

    private fun page(links: List<JSONObject>, header: JSONObject? = null): String =
        JSONObject().put("links", JSONArray(links)).apply { header?.let { put("header", it) } }.toString()

    // --- posts --------------------------------------------------------------------------------

    @Test
    fun `posts are read from the grid with thumbnail, caption and views`() {
        val raw = page(
            listOf(
                link("https://www.tiktok.com/@khaby.lame/video/7709194461913419038", listOf("1.2M"), "https://p16/a.jpeg", "Coach, I'll be right back"),
                link("/@khaby.lame/photo/7704740944427388191", listOf("Đã ghim", "2.3M"), "https://p16/b.jpeg", "Album"),
            ),
        )

        assertEquals(
            listOf(
                ProfilePost("7709194461913419038", "khaby.lame", PostKind.VIDEO, "https://p16/a.jpeg", "Coach, I'll be right back", "1.2M"),
                ProfilePost("7704740944427388191", "khaby.lame", PostKind.PHOTO, "https://p16/b.jpeg", "Album", "2.3M"),
            ),
            ProfileDirectory.parsePosts(raw),
        )
    }

    @Test
    fun `duplicate posts and non-post links are skipped`() {
        val raw = page(
            listOf(
                link("/@a/video/111111111"),
                link("/@a/video/111111111?is_from_webapp=1"),
                link("/@a"),
                link("/@a/live"),
                link("/@a/video/notanumber"),
                link("/tag/funny"),
            ),
        )

        assertEquals(listOf("111111111"), ProfileDirectory.parsePosts(raw).map { it.id })
    }

    @Test
    fun `thumbnails must be https`() {
        val raw = page(listOf(link("/@a/video/123456789", img = "data:image/gif;base64,R0lGOD")))

        assertNull(ProfileDirectory.parsePosts(raw).single().thumbUrl)
    }

    // --- header -------------------------------------------------------------------------------

    @Test
    fun `header reads nickname, counts, bio and avatar`() {
        val header = JSONObject()
            .put("title", "Khabane lame").put("subtitle", "khaby.lame")
            .put("followers", "162.9M").put("following", "82").put("likes", "2.7B")
            .put("bio", "Se vuoi ridere").put("avatar", "https://p16/avatar.jpeg").put("login", false)

        assertEquals(
            ProfileHeader("Khabane lame", "162.9M", "82", "2.7B", "Se vuoi ridere", "https://p16/avatar.jpeg", needsLogin = false, needsVerification = false),
            ProfileDirectory.parseHeader(page(emptyList(), header)),
        )
    }

    @Test
    fun `header notices the login wall of a restricted profile`() {
        val header = JSONObject().put("title", "").put("login", true)

        val parsed = ProfileDirectory.parseHeader(page(emptyList(), header))

        assertTrue(parsed?.needsLogin == true)
        assertNull(parsed?.nickname)
    }

    @Test
    fun `header notices a captcha holding back the posts`() {
        val header = JSONObject().put("title", "Khabane lame").put("captcha", true)

        val parsed = ProfileDirectory.parseHeader(page(emptyList(), header))

        assertTrue(parsed?.needsVerification == true)
        assertFalse(parsed?.needsLogin == true)
    }

    @Test
    fun `header is null when the page has not rendered one`() {
        assertNull(ProfileDirectory.parseHeader(page(emptyList())))
        assertNull(ProfileDirectory.parseHeader("garbage"))
        assertNull(ProfileDirectory.parseHeader(null))
    }

    // --- user search ----------------------------------------------------------------------------

    @Test
    fun `user search results are profile links outside the sidebar`() {
        val raw = page(
            listOf(
                link("/@me_myself", listOf("Hồ sơ"), left = 0.02),
                link("/@khaby.lame", listOf("Khabane lame", "khaby.lame", "162.9M"), "https://p16/k.jpeg"),
                link("https://www.tiktok.com/@khaby.lame?lang=vi", listOf("duplicate")),
                link("/@khaby.lame/video/123456789", listOf("a video")),
                link("/@other_one", listOf("other_one")),
            ),
        )

        assertEquals(
            listOf(
                ProfileHit("khaby.lame", "Khabane lame", "https://p16/k.jpeg", "162.9M"),
                ProfileHit("other_one", "other_one", null, null),
            ),
            ProfileDirectory.parseUsers(raw),
        )
    }

    // --- urls -------------------------------------------------------------------------------------

    @Test
    fun `urls for profile, user search and the embed player`() {
        assertEquals("https://www.tiktok.com/@khaby.lame", ProfileDirectory.profileUrl("khaby.lame"))
        assertEquals("https://www.tiktok.com/search/user?q=kh%C3%A1nh+duy", ProfileDirectory.userSearchUrl(" khánh duy "))
        assertNull(ProfileDirectory.userSearchUrl("  "))
        val player = ProfileDirectory.playerUrl("7709194461913419038")
        assertTrue(player.startsWith("https://www.tiktok.com/player/v1/7709194461913419038?"))
        assertTrue("autoplay" in player)
    }

    @Test
    fun `isUsername accepts handles and rejects free text`() {
        assertTrue(ProfileDirectory.isUsername("khaby.lame"))
        assertTrue(ProfileDirectory.isUsername("a_b.c9"))
        assertFalse(ProfileDirectory.isUsername("khánh duy"))
        assertFalse(ProfileDirectory.isUsername(""))
    }
}

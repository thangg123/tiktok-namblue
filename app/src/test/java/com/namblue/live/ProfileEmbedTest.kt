package com.namblue.live

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [ProfileEmbed]: reading the state TikTok's creator embed page
 * (tiktok.com/embed/@user) ships in its HTML. Shapes mirror real responses.
 */
class ProfileEmbedTest {

    private fun html(key: String, entry: JSONObject): String {
        val state = JSONObject().put("source", JSONObject().put("data", JSONObject().put(key, entry).put("strategy", JSONObject())))
        return """<html><head></head><body><script id="__FRONTITY_CONNECT_STATE__" type="application/json">$state</script>
            <script id="__LOADABLE_REQUIRED_CHUNKS__" type="application/json">[]</script></body></html>"""
    }

    private val userInfo = JSONObject()
        .put("uniqueId", "khaby.lame").put("nickname", "Khabane lame")
        .put("avatarThumbUrl", "https://p19/avatar.jpeg")
        .put("followerCount", 162_900_000L).put("followingCount", 82).put("heartCount", -1_598_201_256L)
        .put("signature", "Se vuoi ridere").put("privateAccount", false)

    private fun video(id: String, play: String, width: Int, views: Long, private: Boolean = false) = JSONObject()
        .put("id", id).put("authorUniqueId", "khaby.lame").put("desc", "  caption $id  ")
        .put("coverUrl", "https://p16/$id.jpeg").put("playAddr", play)
        .put("width", width).put("height", if (width == 0) 0 else 1280)
        .put("playCount", views).put("privateItem", private)

    @Test
    fun `reads the header and the latest posts`() {
        val entry = JSONObject().put("isError", false).put("userInfo", userInfo).put(
            "videoList",
            JSONArray()
                .put(video("7709194461913419038", "https://vx/v.mp4", 720, 1_234_567))
                .put(video("7704774145829063967", "", 0, 184_800)),
        )

        val result = ProfileEmbed.parse(html("/embed/@khaby.lame", entry), "khaby.lame")

        assertTrue("expected Ok, was $result", result is ProfileEmbed.Result.Ok)
        result as ProfileEmbed.Result.Ok
        assertEquals(
            ProfileHeader(
                nickname = "Khabane lame", followers = "162.9M", following = "82", likes = "2.6B",
                bio = "Se vuoi ridere", avatarUrl = "https://p19/avatar.jpeg", needsLogin = false, needsVerification = false,
                followerCount = 162_900_000L,
            ),
            result.header,
        )
        assertEquals(
            listOf(
                ProfilePost("7709194461913419038", "khaby.lame", PostKind.VIDEO, "https://p16/7709194461913419038.jpeg", "caption 7709194461913419038", "1.2M"),
                ProfilePost("7704774145829063967", "khaby.lame", PostKind.PHOTO, "https://p16/7704774145829063967.jpeg", "caption 7704774145829063967", "184.8K"),
            ),
            result.posts,
        )
    }

    @Test
    fun `private posts are left out`() {
        val entry = JSONObject().put("isError", false).put("userInfo", userInfo).put(
            "videoList",
            JSONArray().put(video("111111111", "https://vx/a.mp4", 720, 5)).put(video("222222222", "https://vx/b.mp4", 720, 5, private = true)),
        )

        val result = ProfileEmbed.parse(html("/embed/@khaby.lame", entry), "khaby.lame") as ProfileEmbed.Result.Ok

        assertEquals(listOf("111111111"), result.posts.map { it.id })
    }

    @Test
    fun `restricted or unknown creators come back unavailable`() {
        // Real answer for a creator with audience controls on.
        val entry = JSONObject().put("isError", true).put("errorCode", 10101).put("pageName", "error")

        assertEquals(ProfileEmbed.Result.Unavailable, ProfileEmbed.parse(html("/embed/@namblueraudua", entry), "namblueraudua"))
    }

    @Test
    fun `the page key is matched case-insensitively`() {
        val entry = JSONObject().put("isError", false).put("userInfo", userInfo).put("videoList", JSONArray())

        assertTrue(ProfileEmbed.parse(html("/embed/@Khaby.Lame", entry), "khaby.lame") is ProfileEmbed.Result.Ok)
    }

    @Test
    fun `pages without the state script are unreadable`() {
        assertEquals(ProfileEmbed.Result.Unreadable, ProfileEmbed.parse("<html>captcha</html>", "a"))
        assertEquals(ProfileEmbed.Result.Unreadable, ProfileEmbed.parse(null, "a"))
        assertEquals(
            ProfileEmbed.Result.Unreadable,
            ProfileEmbed.parse("""<script id="__FRONTITY_CONNECT_STATE__">{not json</script>""", "a"),
        )
    }

    @Test
    fun `counts beyond a billion are shortened and overflowed likes are repaired`() {
        assertEquals("2.6B", LiveProfiles.formatViewers(2_696_766_040L))
        assertEquals(2_696_766_040L, ProfileEmbed.unsignedCount(-1_598_201_256L))
        assertEquals(82L, ProfileEmbed.unsignedCount(82L))
    }
}

package com.namblue.live

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [ProfileFeed]: reading the post-list responses the profile page itself fetched
 * (api/post/item_list), as captured by [ProfileFeed.CAPTURE_JS]. Item shapes mirror TikTok's.
 */
class ProfileFeedTest {

    private fun video(id: String, views: Long?, cover: String = "https://p16/$id.jpeg") = JSONObject()
        .put("id", id).put("desc", "  video $id ")
        .put("author", JSONObject().put("uniqueId", "khaby.lame"))
        .put("video", JSONObject().put("cover", cover).put("originCover", "https://p16/origin-$id.jpeg"))
        .apply { views?.let { put("stats", JSONObject().put("playCount", it)) } }

    private fun urls(vararg list: String) = JSONObject().put("imageURL", JSONObject().put("urlList", JSONArray(list.toList())))

    /** Captured shape (CAPTURE_JS keeps the first address of each picture and the music link). */
    private fun photo(id: String) = JSONObject()
        .put("id", id).put("desc", "album")
        .put("author", JSONObject().put("uniqueId", "khaby.lame"))
        .put("video", JSONObject().put("cover", ""))
        .put("statsV2", JSONObject().put("playCount", "184800"))
        .put("music", JSONObject().put("playUrl", "https://sf16/music.mp3"))
        .put(
            "imagePost",
            JSONObject()
                .put("cover", urls("https://p16/album-cover.jpeg"))
                .put("images", JSONArray().put(urls("https://p16/1.jpeg")).put(urls("https://p16/2.jpeg")).put(urls("http://insecure/3.jpeg"))),
        )

    /** What CAPTURE_JS keeps: every captured page, in order, with TikTok's hasMore flag. */
    private fun captured(vararg pages: Pair<List<JSONObject>, Boolean>): String =
        JSONObject().put(
            "pages",
            JSONArray(pages.map { (items, more) -> JSONObject().put("itemList", JSONArray(items)).put("hasMore", more) }),
        ).toString()

    @Test
    fun `items from all captured pages become posts in order`() {
        val raw = captured(
            listOf(video("7709194461913419038", 1_234_567)) to true,
            listOf(photo("7704774145829063967")) to false,
        )

        val feed = ProfileFeed.parse(raw)

        assertEquals(
            listOf(
                ProfilePost("7709194461913419038", "khaby.lame", PostKind.VIDEO, "https://p16/7709194461913419038.jpeg", "video 7709194461913419038", "1.2M"),
                ProfilePost(
                    "7704774145829063967", "khaby.lame", PostKind.PHOTO, "https://p16/album-cover.jpeg", "album", "184.8K",
                    images = listOf("https://p16/1.jpeg", "https://p16/2.jpeg"), musicUrl = "https://sf16/music.mp3",
                ),
            ),
            feed.posts,
        )
        assertFalse(feed.hasMore)
    }

    @Test
    fun `hasMore follows the last captured page`() {
        assertTrue(ProfileFeed.parse(captured(listOf(video("111111111", 1)) to true)).hasMore)
    }

    @Test
    fun `nothing captured yet means more may still come`() {
        val feed = ProfileFeed.parse(captured())

        assertEquals(emptyList<ProfilePost>(), feed.posts)
        assertTrue(feed.hasMore)
    }

    @Test
    fun `duplicates across pages are dropped and the origin cover is the fallback`() {
        val raw = captured(
            listOf(video("111111111", 5), video("222222222", null, cover = "")) to true,
            listOf(video("111111111", 5)) to true,
        )

        val posts = ProfileFeed.parse(raw).posts

        assertEquals(listOf("111111111", "222222222"), posts.map { it.id })
        assertEquals("https://p16/origin-222222222.jpeg", posts[1].thumbUrl)
        assertNull(posts[1].views)
    }

    @Test
    fun `accepts the quoted string evaluateJavascript returns, and garbage`() {
        val quoted = JSONObject.quote(captured(listOf(video("333333333", 7)) to false))

        assertEquals(listOf("333333333"), ProfileFeed.parse(quoted).posts.map { it.id })
        assertEquals(emptyList<ProfilePost>(), ProfileFeed.parse("<html/>").posts)
        assertEquals(emptyList<ProfilePost>(), ProfileFeed.parse(null).posts)
    }
}

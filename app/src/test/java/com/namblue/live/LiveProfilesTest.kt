package com.namblue.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure tests for [LiveProfiles]: reading api-live/user/room and filling gaps in a [LiveChannel]. */
class LiveProfilesTest {

    // Shape captured from api-live/user/room for a live user.
    private val apiBody = """
        {"data":{"user":{"uniqueId":"typicalgamer","nickname":"Typical Gamer",
          "avatarThumb":"https://p16/thumb.jpeg","avatarLarger":"https://p16/large.jpeg","status":2},
          "liveRoom":{"title":"PIRATE TOKEN MARATHON!","status":2,
          "liveRoomStats":{"enterCount":776803,"userCount":23761}}},"statusCode":0}
    """.trimIndent()

    @Test
    fun `parse reads nickname, thumbnail avatar, title and viewers`() {
        assertEquals(
            LiveProfile(nickname = "Typical Gamer", avatarUrl = "https://p16/thumb.jpeg", title = "PIRATE TOKEN MARATHON!", viewers = 23761),
            LiveProfiles.parse(apiBody),
        )
    }

    @Test
    fun `blank fields and non-https avatars are dropped`() {
        val body = """{"data":{"user":{"nickname":"","avatarThumb":"http://x/a.jpg"},"liveRoom":{"title":""}}}"""

        assertEquals(LiveProfile(null, null, null, null), LiveProfiles.parse(body))
    }

    @Test
    fun `parse returns null without a user`() {
        assertNull(LiveProfiles.parse("""{"data":{},"statusCode":10202}"""))
        assertNull(LiveProfiles.parse("<html>captcha</html>"))
        assertNull(LiveProfiles.parse(null))
    }

    @Test
    fun `merge fills only what the scraped card lacked`() {
        val scraped = LiveChannel("typicalgamer", "typicalgamer", null, null, null)
        val profile = LiveProfile("Typical Gamer", "https://p16/thumb.jpeg", "Marathon", 23761)

        assertEquals(
            LiveChannel("typicalgamer", "Typical Gamer", "Marathon", "23.7K", "https://p16/thumb.jpeg"),
            LiveProfiles.merge(scraped, profile),
        )
    }

    @Test
    fun `merge keeps what the page already showed`() {
        val scraped = LiveChannel("a", "Shown Name", "Shown title", "1,2K", "https://p16/shown.jpeg")
        val profile = LiveProfile("Api Name", "https://p16/api.jpeg", "Api title", 999)

        assertEquals(scraped, LiveProfiles.merge(scraped, profile))
    }

    @Test
    fun `viewer counts are shortened the way TikTok shows them`() {
        assertEquals("0", LiveProfiles.formatViewers(0))
        assertEquals("109", LiveProfiles.formatViewers(109))
        assertEquals("1K", LiveProfiles.formatViewers(1_000))
        assertEquals("23.7K", LiveProfiles.formatViewers(23_761))
        assertEquals("1.2M", LiveProfiles.formatViewers(1_250_000))
    }
}

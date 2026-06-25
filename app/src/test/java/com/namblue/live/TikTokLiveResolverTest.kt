package com.namblue.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-parsing tests for [TikTokLiveResolver]. Payloads mirror the real responses
 * verified against TikTok's api-live and webcast endpoints (no network here).
 */
class TikTokLiveResolverTest {

    // --- extractLive: webcast/room/info shapes ---------------------------------------------

    @Test
    fun `live webcast response prefers the single-string HLS url`() {
        val json = """
            {"status_code":0,"data":{"status":2,"stream_url":{
              "hls_pull_url":"https://pull-hls.tiktokcdn.com/stage/stream-123_hd/index.m3u8?expire=1&sign=x",
              "flv_pull_url":{
                "HD1":"https://pull-flv.tiktokcdn.com/stage/stream-123_hd.flv?e=1",
                "SD1":"https://pull-flv.tiktokcdn.com/stage/stream-123_ld.flv?e=1"
              },
              "rtmp_pull_url":"rtmp://pull.tiktokcdn.com/stage/stream-123"
            }}}
        """.trimIndent()

        val result = TikTokLiveResolver.extractLive(json)
        assertTrue("expected Live, was $result", result is LiveStatus.Live)
        result as LiveStatus.Live
        assertEquals(StreamType.HLS, result.type)
        assertTrue(result.url.contains("index.m3u8"))
    }

    @Test
    fun `offline webcast response (status_code 4003110) yields Offline`() {
        val json = """{"status_code":4003110,"data":{},"extra":{"now":1782312250}}"""
        assertEquals(LiveStatus.Offline, TikTokLiveResolver.extractLive(json))
    }

    @Test
    fun `flv-only response yields FLV and picks the higher quality`() {
        // HLS empty -> fall back to FLV; HD1 must win over SD1.
        val json = """
            {"data":{"stream_url":{
              "hls_pull_url":"",
              "flv_pull_url":{
                "SD1":"https://pull-flv.tiktokcdn.com/stage/stream-9_ld.flv?e=1",
                "HD1":"https://pull-flv.tiktokcdn.com/stage/stream-9_hd.flv?e=1"
              }
            }}}
        """.trimIndent()

        val result = TikTokLiveResolver.extractLive(json)
        assertTrue("expected Live, was $result", result is LiveStatus.Live)
        result as LiveStatus.Live
        assertEquals(StreamType.PROGRESSIVE, result.type)
        assertTrue("expected HD1 url, was ${result.url}", result.url.contains("_hd.flv"))
    }

    @Test
    fun `double-encoded stream_data string is descended into`() {
        // api-live shape: liveRoom.streamData.pull_data.stream_data is a JSON-encoded STRING.
        val inner = """{"data":{"origin":{"main":{"flv":"https://x.tiktokcdn.com/o.flv?e=1","hls":"https://x.tiktokcdn.com/o.m3u8?e=1"}}}}"""
        val escaped = org.json.JSONObject.quote(inner) // safely embed as a JSON string value
        val json = """{"data":{"liveRoom":{"streamData":{"pull_data":{"stream_data":$escaped}}}}}"""

        val result = TikTokLiveResolver.extractLive(json)
        assertTrue("expected Live, was $result", result is LiveStatus.Live)
        result as LiveStatus.Live
        assertEquals(StreamType.HLS, result.type)
        assertTrue(result.url.contains("o.m3u8"))
    }

    @Test
    fun `prefers the origin quality over the default url and transcodes`() {
        val hd = quality(res = "1280x720", vbitrate = 1_800_000, hls = "https://x/hd.m3u8", flv = "https://x/hd.flv")
        val origin = quality(res = "", vbitrate = 0, hls = "https://x/origin.m3u8", flv = "https://x/origin.flv")
        val streamData = org.json.JSONObject().put("data", org.json.JSONObject().put("hd", hd).put("origin", origin))
        val roomInfo = roomInfoWith(defaultHls = "https://x/default_hd.m3u8", streamData = streamData.toString())

        val result = TikTokLiveResolver.extractLive(roomInfo)
        assertTrue("expected Live, was $result", result is LiveStatus.Live)
        result as LiveStatus.Live
        assertEquals(StreamType.HLS, result.type)
        assertTrue("expected origin url, was ${result.url}", result.url.contains("origin.m3u8"))
    }

    @Test
    fun `picks the highest resolution when there is no origin`() {
        val ld = quality(res = "640x360", vbitrate = 600_000, hls = "https://x/ld.m3u8", flv = null)
        val hd = quality(res = "1280x720", vbitrate = 1_800_000, hls = "https://x/hd.m3u8", flv = null)
        val streamData = org.json.JSONObject().put("data", org.json.JSONObject().put("ld", ld).put("hd", hd))
        val roomInfo = roomInfoWith(defaultHls = null, streamData = streamData.toString())

        val result = TikTokLiveResolver.extractLive(roomInfo)
        assertTrue("expected Live, was $result", result is LiveStatus.Live)
        result as LiveStatus.Live
        assertEquals(StreamType.HLS, result.type)
        assertTrue("expected hd url, was ${result.url}", result.url.contains("hd.m3u8"))
    }

    private fun quality(res: String, vbitrate: Int, hls: String?, flv: String?): org.json.JSONObject {
        val sdk = org.json.JSONObject().put("resolution", res).put("vbitrate", vbitrate)
        val main = org.json.JSONObject().put("sdk_params", sdk.toString())
        if (hls != null) main.put("hls", hls)
        if (flv != null) main.put("flv", flv)
        return org.json.JSONObject().put("main", main)
    }

    private fun roomInfoWith(defaultHls: String?, streamData: String): String {
        val streamUrl = org.json.JSONObject()
            .put("live_core_sdk_data", org.json.JSONObject().put("pull_data", org.json.JSONObject().put("stream_data", streamData)))
        if (defaultHls != null) streamUrl.put("hls_pull_url", defaultHls)
        return org.json.JSONObject().put("data", org.json.JSONObject().put("stream_url", streamUrl)).toString()
    }

    @Test
    fun `no stream urls anywhere yields Offline`() {
        val json = """{"status_code":0,"data":{"status":4,"title":"hello"}}"""
        assertEquals(LiveStatus.Offline, TikTokLiveResolver.extractLive(json))
    }

    @Test
    fun `non-JSON 2xx body is reported as Error not Offline`() {
        // A WAF/captcha/HTML interstitial served with HTTP 200 must not look like "offline".
        assertTrue(TikTokLiveResolver.extractLive("Captcha required") is LiveStatus.Error)
        assertTrue(TikTokLiveResolver.extractLive("<html>blocked</html>") is LiveStatus.Error)
    }

    // --- parseRoomId: api-live/user/room shapes --------------------------------------------

    @Test
    fun `parseRoomId reads data_user_roomId`() {
        val json = """{"data":{"user":{"roomId":"7654972737091980040","uniqueId":"namblueraudua"}},"statusCode":0}"""
        assertEquals("7654972737091980040", TikTokLiveResolver.parseRoomId(json))
    }

    @Test
    fun `parseRoomId returns null for user_not_found`() {
        val json = """{"data":null,"statusCode":19881007,"message":"user_not_found"}"""
        assertNull(TikTokLiveResolver.parseRoomId(json))
    }

    @Test
    fun `parseRoomId treats roomId 0 as null`() {
        val json = """{"data":{"user":{"roomId":"0"}}}"""
        assertNull(TikTokLiveResolver.parseRoomId(json))
    }

    @Test
    fun `parseIsLive true when status is 2`() {
        assertTrue(TikTokLiveResolver.parseIsLive("""{"data":{"user":{"status":2},"liveRoom":{"status":2}}}"""))
        assertTrue(TikTokLiveResolver.parseIsLive("""{"data":{"liveRoom":{"status":2}}}"""))
    }

    @Test
    fun `parseIsLive false when offline or missing`() {
        assertTrue(!TikTokLiveResolver.parseIsLive("""{"data":{"user":{"status":4},"liveRoom":{"status":4}}}"""))
        assertTrue(!TikTokLiveResolver.parseIsLive("""{"data":null}"""))
    }
}

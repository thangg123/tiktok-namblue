package com.namblue.live

import org.json.JSONObject

/** What TikTok's profile API says about a live creator; any field may be missing. */
data class LiveProfile(val nickname: String?, val avatarUrl: String?, val title: String?, val viewers: Long?)

/**
 * Fills in cards that tiktok.com/live rendered without details, using the api-live/user/room
 * answer the preview's resolver already fetched for the focused channel.
 *
 * Never call that endpoint once per card: a burst of a few dozen requests gets the whole IP
 * blocked (HTTP 403) — and the player depends on the same endpoint.
 */
object LiveProfiles {

    private const val THOUSAND = 1_000L
    private const val MILLION = 1_000_000L
    private const val BILLION = 1_000_000_000L

    /** Read api-live/user/room; null when there is no user in it. Pure; unit-tested. */
    fun parse(body: String?): LiveProfile? = runCatching {
        val data = JSONObject(body.orEmpty()).optJSONObject("data") ?: return null
        val user = data.optJSONObject("user") ?: return null
        val room = data.optJSONObject("liveRoom")
        val viewers = room?.optJSONObject("liveRoomStats")?.takeIf { it.has("userCount") }?.optLong("userCount")
        LiveProfile(
            nickname = user.optString("nickname", "").trim().ifEmpty { null },
            avatarUrl = user.optString("avatarThumb", "").takeIf { it.startsWith("https://") },
            title = room?.optString("title", "")?.trim()?.ifEmpty { null },
            viewers = viewers,
        )
    }.getOrNull()

    /** [channel] with its gaps filled from [profile]; what the page showed always wins. Pure. */
    fun merge(channel: LiveChannel, profile: LiveProfile): LiveChannel = channel.copy(
        nickname = channel.nickname.takeUnless { it == channel.username } ?: profile.nickname ?: channel.nickname,
        title = channel.title ?: profile.title,
        viewers = channel.viewers ?: profile.viewers?.let(::formatViewers),
        avatarUrl = channel.avatarUrl ?: profile.avatarUrl,
    )

    /** 109 → "109", 23761 → "23.7K", 1250000 → "1.2M", 2.6e9 → "2.6B" (truncated). Pure. */
    fun formatViewers(count: Long): String {
        val (unit, suffix) = when {
            count >= BILLION -> BILLION to "B"
            count >= MILLION -> MILLION to "M"
            count >= THOUSAND -> THOUSAND to "K"
            else -> return count.toString()
        }
        val whole = count / unit
        val tenth = count % unit * 10 / unit
        return if (tenth == 0L) "$whole$suffix" else "$whole.$tenth$suffix"
    }
}

package com.namblue.live

/**
 * Hands the full post list (album pictures, music) from the profile to the player without
 * stuffing it into the Intent, whose size is capped. The Intent still carries the ids, so a
 * recreated player falls back to TikTok's embed player if this is empty.
 */
object PostQueue {
    @Volatile
    var posts: List<ProfilePost> = emptyList()
}

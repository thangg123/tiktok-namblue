package com.namblue.live

/** Container of a resolved stream, so the player can hint ExoPlayer correctly. */
enum class StreamType { HLS, PROGRESSIVE }

/**
 * Outcome of trying to resolve a live stream.
 *
 * Kept deliberately tiny — the app only needs "can I play something, and if not, was
 * the user offline or did something break".
 */
sealed interface LiveStatus {

    /** The user is live. [url] is directly playable; [type] hints the container. */
    data class Live(val url: String, val type: StreamType) : LiveStatus

    /** The user exists but is not currently streaming. */
    data object Offline : LiveStatus

    /** Status could not be determined (network/parse failure). [message] is for logs only. */
    data class Error(val message: String) : LiveStatus
}

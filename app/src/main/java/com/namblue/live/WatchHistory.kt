package com.namblue.live

import android.content.Context

/**
 * Recently watched TikTok usernames, newest first, persisted in SharedPreferences. Lives and
 * profiles keep separate lists ([LIVES], [PROFILES]); every persistence call takes the list.
 *
 * The list logic ([added], [removed], [encode], [decode]) is pure so it can be unit-tested
 * without Android; [load], [record] and [delete] are the thin persistence wrappers.
 */
object WatchHistory {

    const val MAX_ENTRIES = 12

    /** Live channels watched (the "Other" screen and the live list). */
    const val LIVES = "watch_history"

    /** Profiles opened in the profile browser. */
    const val PROFILES = "profile_history"

    private const val KEY_USERNAMES = "usernames"
    private const val SEPARATOR = "\n"

    fun load(context: Context, list: String = LIVES): List<String> =
        decode(prefs(context, list).getString(KEY_USERNAMES, null))

    /** Save [username] as the most recently watched; returns the updated list. */
    fun record(context: Context, username: String, list: String = LIVES): List<String> =
        added(load(context, list), username).also { save(context, list, it) }

    /** Remove [username] from the history; returns the updated list. */
    fun delete(context: Context, username: String, list: String = LIVES): List<String> =
        removed(load(context, list), username).also { save(context, list, it) }

    fun added(history: List<String>, username: String): List<String> {
        val name = username.trim()
        if (name.isEmpty()) return history
        val rest = history.filterNot { it.equals(name, ignoreCase = true) }
        return (listOf(name) + rest).take(MAX_ENTRIES)
    }

    fun removed(history: List<String>, username: String): List<String> =
        history.filterNot { it.equals(username, ignoreCase = true) }

    fun encode(history: List<String>): String = history.joinToString(SEPARATOR)

    fun decode(raw: String?): List<String> =
        raw.orEmpty().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private fun save(context: Context, list: String, history: List<String>) {
        prefs(context, list).edit().putString(KEY_USERNAMES, encode(history)).apply()
    }

    private fun prefs(context: Context, list: String) =
        context.getSharedPreferences(list, Context.MODE_PRIVATE)
}
